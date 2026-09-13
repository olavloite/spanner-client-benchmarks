import {Database} from '@google-cloud/spanner';
import {Histogram, Counter} from '@opentelemetry/api';
import {Worker, WorkerOptions} from 'worker_threads';
import * as path from 'path';
import * as os from 'os';
import * as fs from 'fs';
import * as net from 'net';
import {LoadType} from './load-type';
import {ResourceMonitor} from '../utils/resource-monitor';
import {BenchmarkWorkerData} from './benchmark-worker';
export {LoadType};

export interface IBenchmark {
  execute(
    database: Database,
    tableName: string,
    minId: number,
    maxId: number,
  ): Promise<void>;
  getName(): string;
  getType(): string;
}

/**
 * Abstract base class for all client benchmarks.
 * Implements a high-precision adaptive asynchronous Poisson workload scheduler.
 */
export abstract class AbstractBenchmark implements IBenchmark {
  protected database: Database;
  protected latencyHistogram: Histogram;
  protected operationCounter: Counter;
  protected errorCounter: Counter;
  protected tableName: string;
  protected minId: number;
  protected maxId: number;
  protected tps: number;
  protected threads: number;
  protected durationMs: number | null;
  protected forAlerting: boolean;
  protected burstFactor: number;
  protected burstDuration: number;
  protected burstFraction: number;
  protected loadType: LoadType;
  protected cycleDurationMs: number | null;
  protected peakFactor: number;
  protected workers: number;
  protected host?: string;

  private attributes: Record<string, any>;
  private activeTasks = 0;
  private taskQueue: number[] = [];
  private lastQueueLogTime = 0;
  private isStopped = false;
  private worker: Worker | null = null;
  private socketClient?: net.Socket;
  private rBurst: number;
  private rNormal: number;

  private workersList: Worker[] = [];
  private workerInFlight: number[] = [];
  private workerActive: boolean[] = [];
  private workersCleanedUp = false;
  private nextWorkerIndex = 0;
  private nextTaskId = 1;

  private memoryUsageHistogram: Histogram | null = null;
  private cpuUtilizationHistogram: Histogram | null = null;
  private resourceProbeIntervalStr = '10s';
  private resourceMonitor: ResourceMonitor | null = null;

  constructor(
    database: Database,
    latencyHistogram: Histogram,
    operationCounter: Counter,
    errorCounter: Counter,
    memoryUsageHistogram: Histogram | null,
    cpuUtilizationHistogram: Histogram | null,
    resourceProbeIntervalStr: string,
    tableName: string,
    minId: number,
    maxId: number,
    tps: number,
    threads: number,
    durationMs: number | null,
    forAlerting: boolean,
    benchmarkName = '',
    loadType: LoadType = LoadType.Steady,
    cycleDurationMs: number | null = null,
    peakFactor = 2.0,
    burstFactor = 1.0,
    burstDuration = 1.0,
    burstFraction = 0.1,
    isMock = false,
    workers = 1,
    host?: string,
  ) {
    this.database = database;
    this.latencyHistogram = latencyHistogram;
    this.operationCounter = operationCounter;
    this.errorCounter = errorCounter;
    this.memoryUsageHistogram = memoryUsageHistogram;
    this.cpuUtilizationHistogram = cpuUtilizationHistogram;
    this.resourceProbeIntervalStr = resourceProbeIntervalStr;
    this.tableName = tableName;
    this.minId = minId;
    this.maxId = maxId;
    this.tps = tps;
    this.threads = threads;
    this.durationMs = durationMs;
    this.forAlerting = forAlerting;
    this.loadType = loadType;
    this.cycleDurationMs = cycleDurationMs;
    this.peakFactor = peakFactor;
    this.burstFactor = burstFactor;
    this.burstDuration = burstDuration;
    this.burstFraction = burstFraction;
    this.workers = workers > 0 ? workers : 1;
    this.host = host;

    this.rBurst = this.tps * this.burstFactor;
    this.rNormal =
      (this.tps - this.burstFraction * this.rBurst) /
      (1.0 - this.burstFraction);

    // Pre-create attributes to avoid object creation overhead on the hot path (parity with Go and Java)
    this.attributes = {
      benchmark_type: isMock ? `${this.getType()}-mock` : this.getType(),
      tps: this.tps.toFixed(1),
      for_alerting: this.forAlerting,
      benchmark_name: benchmarkName,
      client: 'node-client',
      load_type: this.loadType,
      burst_factor: this.burstFactor,
      burst_duration: this.burstDuration,
      burst_fraction: this.burstFraction,
      cycle_duration_ms: this.cycleDurationMs || 0,
      peak_factor: this.peakFactor,
      transaction_type: 'none',
    };
  }

  abstract execute(
    database: Database,
    tableName: string,
    minId: number,
    maxId: number,
  ): Promise<void>;
  abstract getName(): string;
  abstract getType(): string;

  /**
   * Runs the workload generator loop until the duration is reached or stop() is called.
   */
  public async run(): Promise<void> {
    console.log(`Starting ${this.getName()}`);
    console.log(
      `Parameters: TPS=${this.tps}, Max Workers=${this.threads}, MinID=${this.minId}, MaxID=${this.maxId}${
        this.workers > 1 ? `, Worker Threads=${this.workers}` : ''
      }`,
    );

    this.startResourceMonitoring();

    if (this.workers > 1) {
      await this.initWorkers();
    }

    let timeoutId: NodeJS.Timeout | null = null;
    const durationMs = this.durationMs;
    if (durationMs !== null) {
      timeoutId = setTimeout(() => {
        console.log(
          'Benchmark duration reached. Stopping workload generator...',
        );
        this.stop();
      }, durationMs);
    }

    if (this.loadType === LoadType.ClosedLoop) {
      if (this.workers > 1) {
        for (let i = 0; i < this.threads; i++) {
          this.submitTaskMultiWorker();
        }
      } else {
        for (let i = 0; i < this.threads; i++) {
          this.runClosedLoop();
        }
      }

      // Block and wait until the benchmark is stopped and all tasks are finished or cancelled
      return new Promise<void>(resolve => {
        const waiter = setInterval(() => {
          if (this.isStopped && this.activeTasks === 0) {
            clearInterval(waiter);
            if (timeoutId) clearTimeout(timeoutId);
            this.cleanupWorkers();
            resolve();
          }
        }, 100);
      });
    }

    const socketPath = process.env.SPANNER_BENCHMARK_SOCKET;
    if (socketPath) {
      this.runSocketTriggeredGenerator(socketPath);
    } else {
      const sab = new SharedArrayBuffer(4);
      const int32Array = new Int32Array(sab);

      const workerPath = path.join(__dirname, 'scheduler-worker.js');
      this.worker = new Worker(workerPath, {
        workerData: {
          tps: this.tps,
          loadType: this.loadType,
          burstFactor: this.burstFactor,
          burstDuration: this.burstDuration,
          burstFraction: this.burstFraction,
          cycleDurationMs: this.cycleDurationMs,
          peakFactor: this.peakFactor,
          rBurst: this.rBurst,
          rNormal: this.rNormal,
          sab: sab,
        },
      });

      this.worker.on('message', msg => {
        if (msg.type === 'spawn') {
          for (let i = 0; i < msg.count; i++) {
            this.submitTask();
          }
        }
      });

      this.worker.on('error', err => {
        console.error('Worker error:', err);
      });

      this.worker.on('exit', code => {
        if (code !== 0 && !this.isStopped) {
          console.error(`Scheduler worker stopped with exit code ${code}`);
        }
      });
    }

    // Block and wait until the benchmark is stopped and all tasks are finished or cancelled
    return new Promise<void>(resolve => {
      const waiter = setInterval(() => {
        if (
          this.isStopped &&
          this.activeTasks === 0 &&
          this.taskQueue.length === 0
        ) {
          clearInterval(waiter);
          if (timeoutId) clearTimeout(timeoutId);
          this.cleanupWorkers();
          console.log(
            'All outstanding active tasks completed. Benchmark run finished.',
          );
          resolve();
        }
      }, 100);
    });
  }

  /**
   * Gracefully requests the workload generator to stop spawning new tasks.
   */
  public stop(): void {
    this.isStopped = true;
    this.taskQueue = [];
    if (this.worker) {
      this.worker.terminate();
    }
    if (this.socketClient) {
      this.socketClient.destroy();
    }
    if (this.resourceMonitor) {
      this.resourceMonitor.stop();
    }
    this.cleanupWorkers();
  }

  private runSocketTriggeredGenerator(socketPath: string): void {
    console.log(
      `Connecting to workload generator sidecar socket: ${socketPath}`,
    );
    this.socketClient = net.createConnection(socketPath, () => {
      console.log('Connected to workload generator sidecar. Sending READY...');
      this.socketClient?.write('READY\n');
    });

    this.socketClient.on('data', data => {
      // Each trigger event is 0x01 byte. If multiple bytes are received, run submitTask() multiple times.
      for (let i = 0; i < data.length; i++) {
        if (data[i] === 0x01) {
          this.submitTask();
        }
      }
    });

    this.socketClient.on('error', err => {
      console.error('Socket client error:', err);
      process.exit(1);
    });

    this.socketClient.on('close', () => {
      console.log('Workload generator socket connection closed.');
      this.stop(); // Stops resource monitoring and triggers waiter cleanup
    });
  }

  /**
   * Pushes a task into active execution if concurrency allows, otherwise queues it.
   */
  private submitTask(): void {
    if (this.workers > 1) {
      this.submitTaskMultiWorker();
    } else {
      this.submitTaskSingleWorker();
    }
  }

  private submitTaskSingleWorker(): void {
    if (this.activeTasks < this.threads) {
      this.runTask();
    } else {
      this.enqueueTask();
    }
  }

  private submitTaskMultiWorker(): void {
    if (this.activeTasks < this.threads) {
      this.dispatchTaskToWorker();
    } else {
      this.enqueueTask();
    }
  }

  private enqueueTask(): void {
    const queueSize = this.taskQueue.length;
    if (queueSize > 0) {
      const now = Date.now();
      if (now - this.lastQueueLogTime > 1000) {
        console.log(
          `Queue size: ${queueSize} (concurrency limit reached, tasks are queueing)`,
        );
        this.lastQueueLogTime = now;
      }
    }
    if (this.taskQueue.length < 1000000) {
      this.taskQueue.push(1);
    } else {
      // Task queue is full, drop task to simulate unbounded network queue limits (parity with Go's 1M limit)
      console.error('Task dropped: workload queue is full (1M tasks exceeded)');
    }
  }

  private dispatchTaskToWorker(): void {
    if (this.workersList.length === 0 || this.isStopped) {
      return;
    }

    // Find all active workers
    const activeIndices: number[] = [];
    for (let i = 0; i < this.workers; i++) {
      if (this.workerActive[i]) {
        activeIndices.push(i);
      }
    }

    if (activeIndices.length === 0) {
      console.error('All benchmark workers have terminated; dropping task');
      this.errorCounter.add(1, this.getAttributes());
      return;
    }

    this.activeTasks++;

    // Select the least loaded active worker, breaking ties with round-robin starting from nextWorkerIndex
    let selectedIndex = activeIndices[0];
    let minInFlight = Number.POSITIVE_INFINITY;
    for (let i = 0; i < activeIndices.length; i++) {
      const idx =
        activeIndices[(this.nextWorkerIndex + i) % activeIndices.length];
      if (this.workerInFlight[idx] < minInFlight) {
        minInFlight = this.workerInFlight[idx];
        selectedIndex = idx;
      }
    }
    this.nextWorkerIndex = (this.nextWorkerIndex + 1) % activeIndices.length;
    this.workerInFlight[selectedIndex]++;

    const taskId = this.nextTaskId++;
    try {
      this.workersList[selectedIndex].postMessage({
        type: 'execute',
        taskId,
      });
    } catch (err) {
      console.error(
        `Failed to post execute task to worker ${selectedIndex}:`,
        err,
      );
      this.workerInFlight[selectedIndex] = Math.max(
        0,
        this.workerInFlight[selectedIndex] - 1,
      );
      this.activeTasks = Math.max(0, this.activeTasks - 1);
      this.errorCounter.add(1, this.getAttributes());
    }
  }

  private onWorkerTaskCompleted(workerId: number, msg: any): void {
    this.workerInFlight[workerId] = Math.max(
      0,
      this.workerInFlight[workerId] - 1,
    );
    this.activeTasks = Math.max(0, this.activeTasks - 1);

    const attributes = this.getAttributes();
    if (msg.success) {
      if (typeof msg.latencyUs === 'number' && msg.latencyUs > 0) {
        this.latencyHistogram.record(msg.latencyUs, attributes);
      }
      this.operationCounter.add(1, attributes);
    } else {
      console.error(`Operation failed: ${msg.error}`);
      this.errorCounter.add(1, attributes);
      this.operationCounter.add(1, attributes);
    }

    if (this.loadType === LoadType.ClosedLoop) {
      if (!this.isStopped) {
        this.submitTaskMultiWorker();
      }
    } else {
      // Drain buffered queue slots concurrently as workers become available
      while (
        this.taskQueue.length > 0 &&
        this.activeTasks < this.threads &&
        !this.isStopped
      ) {
        this.taskQueue.shift();
        this.dispatchTaskToWorker();
      }
    }
  }

  private resolveWorkerPath(): string {
    const jsPath = path.join(__dirname, 'benchmark-worker.js');
    if (fs.existsSync(jsPath)) {
      return jsPath;
    }
    const tsPath = path.join(__dirname, 'benchmark-worker.ts');
    if (fs.existsSync(tsPath)) {
      return tsPath;
    }
    return jsPath;
  }

  /**
   * Extracts and sanitizes Spanner connection configuration from the main thread's
   * Database instance so it can be passed to worker threads via workerData.
   *
   * Note on HTML Structured Clone Algorithm:
   * Node.js worker_threads uses structured cloning to pass `workerData`. Complex objects
   * containing functions, EventEmitter instances, or gRPC channels will fail with
   * DataCloneError. We therefore extract only primitive values (strings, numbers, booleans)
   * for database options.
   */
  private buildWorkerConfig(): {
    projectId: string;
    instanceId: string;
    databaseId: string;
    host?: string;
    databaseOptions?: Record<string, any>;
  } {
    const dbAny = this.database as any;
    const projectId =
      dbAny.parent?.parent?.projectId ||
      process.env.GOOGLE_CLOUD_PROJECT ||
      'fake-project';
    const instanceId = dbAny.parent?.id || 'fake-instance';
    const databaseId = this.database.id || 'fake-database';

    let host = this.host;
    if (!host) {
      const spannerOptions = dbAny.parent?.parent?.options;
      if (spannerOptions?.apiEndpoint) {
        host = spannerOptions.port
          ? `${spannerOptions.apiEndpoint}:${spannerOptions.port}`
          : spannerOptions.apiEndpoint;
      } else if (process.env.SPANNER_EMULATOR_HOST) {
        host = process.env.SPANNER_EMULATOR_HOST;
      }
    }

    let databaseOptions: Record<string, any> | undefined;
    if (dbAny.options_) {
      databaseOptions = {};
      for (const [key, val] of Object.entries(dbAny.options_)) {
        if (
          typeof val === 'number' ||
          typeof val === 'string' ||
          typeof val === 'boolean'
        ) {
          databaseOptions[key] = val;
        }
      }
    }

    return {projectId, instanceId, databaseId, host, databaseOptions};
  }

  /**
   * Spawns an individual benchmark worker thread, establishes its message handlers,
   * and awaits its 'ready' handshake signal.
   *
   * @param workerId Zero-based worker thread index
   * @param workerPath Absolute path to worker script (.js or .ts)
   * @param config Connection parameters extracted from the main Spanner instance
   */
  private spawnWorker(
    workerId: number,
    workerPath: string,
    config: {
      projectId: string;
      instanceId: string;
      databaseId: string;
      host?: string;
      databaseOptions?: Record<string, any>;
    },
  ): Promise<void> {
    this.workerInFlight.push(0);
    this.workerActive.push(true);

    const workerData: BenchmarkWorkerData = {
      workerId,
      benchmarkType: this.getType(),
      projectId: config.projectId,
      instanceId: config.instanceId,
      databaseId: config.databaseId,
      host: config.host,
      tableName: this.tableName,
      minId: this.minId,
      maxId: this.maxId,
      numRows: (this as any).numRows,
      databaseOptions: config.databaseOptions,
    };

    const workerOptions: WorkerOptions = {workerData};
    if (workerPath.endsWith('.ts')) {
      // In development/testing when running directly from TypeScript sources,
      // register ts-node so worker thread can load TypeScript files.
      workerOptions.execArgv = ['-r', 'ts-node/register'];
    }

    const worker = new Worker(workerPath, workerOptions);
    this.workersList.push(worker);

    // Set up handshake promise that resolves when worker emits 'ready'
    const readyPromise = new Promise<void>((resolve, reject) => {
      const onMessage = (msg: any) => {
        if (msg && msg.type === 'ready') {
          worker.off('message', onMessage);
          worker.off('error', onError);
          resolve();
        }
      };
      const onError = (err: any) => {
        worker.off('message', onMessage);
        worker.off('error', onError);
        reject(err);
      };
      worker.on('message', onMessage);
      worker.on('error', onError);
    });

    // Listen for task completion events from this worker
    worker.on('message', (msg: any) => {
      if (msg && msg.type === 'completed') {
        this.onWorkerTaskCompleted(workerId, msg);
      }
    });

    worker.on('error', (err: any) => {
      console.error(`Worker ${workerId} error:`, err);
    });

    worker.on('exit', (code: number) => {
      this.workerActive[workerId] = false;

      if (code !== 0 && !this.isStopped) {
        console.error(`Worker ${workerId} stopped with exit code ${code}`);
      }
      // Reclaim any uncompleted in-flight tasks assigned to this worker so the benchmark run does not hang
      const inFlight = this.workerInFlight[workerId];
      this.workerInFlight[workerId] = Number.POSITIVE_INFINITY;
      if (inFlight > 0 && inFlight !== Number.POSITIVE_INFINITY) {
        this.activeTasks = Math.max(0, this.activeTasks - inFlight);
        this.errorCounter.add(inFlight, this.getAttributes());

        // In closed-loop mode, replenish the lost tasks to healthy workers to maintain concurrency target
        if (this.loadType === LoadType.ClosedLoop && !this.isStopped) {
          for (let i = 0; i < inFlight; i++) {
            this.submitTaskMultiWorker();
          }
        }
      }
    });

    return readyPromise;
  }

  /**
   * Initializes the pool of worker threads, resolving the worker script path,
   * building serializable client options, and waiting for all workers to be ready.
   */
  private async initWorkers(): Promise<void> {
    const workerPath = this.resolveWorkerPath();
    console.log(
      `Initializing ${this.workers} benchmark worker threads with script: ${workerPath}`,
    );

    const config = this.buildWorkerConfig();
    const readyPromises: Promise<void>[] = [];

    for (let i = 0; i < this.workers; i++) {
      readyPromises.push(this.spawnWorker(i, workerPath, config));
    }

    await Promise.all(readyPromises);
    console.log(`All ${this.workers} benchmark worker threads ready.`);
  }

  private cleanupWorkers(): void {
    if (this.workersCleanedUp) {
      return;
    }
    this.workersCleanedUp = true;

    for (const w of this.workersList) {
      try {
        w.postMessage({type: 'stop'});
        const timer = setTimeout(() => {
          try {
            w.terminate();
          } catch (e) {}
        }, 5000);
        if (typeof timer.unref === 'function') {
          timer.unref();
        }
      } catch (e) {
        try {
          w.terminate();
        } catch (termErr) {}
      }
    }
  }

  protected shouldMeasureEntireMethod(): boolean {
    return true;
  }

  protected getAttributes(): Record<string, any> {
    return this.attributes;
  }

  /**
   * Executes a task, measures its high-resolution latency, records to histogram, and drains queue.
   */
  private async runTask(): Promise<void> {
    this.activeTasks++;
    const startTimeNs = process.hrtime.bigint();

    try {
      await this.execute(this.database, this.tableName, this.minId, this.maxId);
    } catch (err: any) {
      console.error(`Operation failed: ${err?.message || err}`);
      this.errorCounter.add(1, this.attributes);
    } finally {
      const endTimeNs = process.hrtime.bigint();
      if (this.shouldMeasureEntireMethod()) {
        const latencyUs = Number(endTimeNs - startTimeNs) / 1000;
        this.latencyHistogram.record(latencyUs, this.attributes);
      }
      this.operationCounter.add(1, this.attributes);
      this.activeTasks--;

      // Drain buffered queue slots concurrently as workers become available
      if (
        this.taskQueue.length > 0 &&
        this.activeTasks < this.threads &&
        !this.isStopped
      ) {
        this.taskQueue.shift();
        setImmediate(() => this.runTask());
      }
    }
  }

  /**
   * Calculates the next Poisson arrival delay in nanoseconds.
   * Formula: delaySeconds = -ln(1 - u) / rate, where u is Uniform(0, 1)
   */
  private calculatePoissonDelayNs(rate: number): bigint {
    if (rate <= 0) {
      return 3600000000000n; // 1 hour in nanoseconds
    }
    const u = Math.random();
    // Prevent u being exactly 1.0 which would result in ln(0) -> -Infinity
    const safeU = u === 1.0 ? 0.999999999 : u;
    const delaySeconds = -Math.log(1.0 - safeU) / rate;
    return BigInt(Math.floor(delaySeconds * 1_000_000_000));
  }

  private calculateCurrentRate(
    nowNs: bigint,
    startTimeNs: bigint,
    inBurst: boolean,
  ): number {
    if (this.loadType === LoadType.Spiky) {
      return inBurst ? this.rBurst : this.rNormal;
    } else if (this.loadType === LoadType.Gradual) {
      const elapsedNs = Number(nowNs - startTimeNs);
      const cycleDurationNs = (this.cycleDurationMs || 3600000) * 1000000;
      const amplitude = this.tps * (this.peakFactor - 1.0);
      const angle =
        (2.0 * Math.PI * (elapsedNs % cycleDurationNs)) / cycleDurationNs;
      return this.tps + amplitude * Math.cos(angle - Math.PI);
    }
    return this.tps;
  }

  private async runClosedLoop(): Promise<void> {
    while (!this.isStopped) {
      await this.runTaskClosedLoop();
    }
  }

  private async runTaskClosedLoop(): Promise<void> {
    const startTimeNs = process.hrtime.bigint();
    try {
      await this.execute(this.database, this.tableName, this.minId, this.maxId);
    } catch (err: any) {
      console.error(`Operation failed: ${err?.message || err}`);
      this.errorCounter.add(1, this.attributes);
    } finally {
      const endTimeNs = process.hrtime.bigint();
      if (this.shouldMeasureEntireMethod()) {
        const latencyUs = Number(endTimeNs - startTimeNs) / 1000;
        this.latencyHistogram.record(latencyUs, this.attributes);
      }
      this.operationCounter.add(1, this.attributes);
    }
  }

  private startResourceMonitoring(): void {
    this.resourceMonitor = new ResourceMonitor(
      this.resourceProbeIntervalStr,
      this.memoryUsageHistogram,
      this.cpuUtilizationHistogram,
      this.attributes,
      () => this.isStopped,
    );
    this.resourceMonitor.start();
  }
}
