import {Database} from '@google-cloud/spanner';
import {Histogram, Counter} from '@opentelemetry/api';
import {Worker} from 'worker_threads';
import * as path from 'path';
import * as os from 'os';
import * as net from 'net';
import {LoadType} from './load-type';
import {ResourceMonitor} from '../utils/resource-monitor';
export {LoadType};

export interface LatencyStats {
  min: number;
  max: number;
  avg: number;
  p50: number;
  p90: number;
  p95: number;
  p99: number;
  count: number;
}

/**
 * Thread-safe Reservoir Sampler for tracking in-memory latency statistics and percentiles.
 */
export class ReservoirSampler {
  private limit: number;
  private samples: number[] = [];
  private count = 0;

  constructor(limit = 20000) {
    this.limit = limit;
  }

  public add(val: number): void {
    this.count++;
    if (this.samples.length < this.limit) {
      this.samples.push(val);
    } else {
      const idx = Math.floor(Math.random() * this.count);
      if (idx < this.limit) {
        this.samples[idx] = val;
      }
    }
  }

  public getStats(): LatencyStats | null {
    if (this.samples.length === 0) {
      return null;
    }
    const sorted = [...this.samples].sort((a, b) => a - b);
    const n = sorted.length;
    const total = sorted.reduce((sum, val) => sum + val, 0);
    const avg = total / n;

    const getPercentile = (p: number): number => {
      const idx = Math.max(0, Math.min(n - 1, Math.ceil((p / 100.0) * n) - 1));
      return sorted[idx];
    };

    return {
      min: sorted[0],
      max: sorted[n - 1],
      avg: avg,
      p50: getPercentile(50.0),
      p90: getPercentile(90.0),
      p95: getPercentile(95.0),
      p99: getPercentile(99.0),
      count: this.count,
    };
  }
}

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

  protected attributes: Record<string, any>;
  protected latencySampler = new ReservoirSampler(20000);
  private successCount = 0;
  private errorCount = 0;

  private activeTasks = 0;
  private taskQueue: number[] = [];
  private lastQueueLogTime = 0;
  private isStopped = false;
  private worker: Worker | null = null;
  private socketClient?: net.Socket;
  private rBurst: number;
  private rNormal: number;

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
      `Parameters: TPS=${this.tps}, Max Workers=${this.threads}, MinID=${this.minId}, MaxID=${this.maxId}`,
    );

    this.startResourceMonitoring();
    const startWait = Date.now();

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
      for (let i = 0; i < this.threads; i++) {
        this.runClosedLoop();
      }

      // Block and wait until the benchmark is stopped and all tasks are finished or cancelled
      await new Promise<void>(resolve => {
        const waiter = setInterval(() => {
          if (this.isStopped) {
            clearInterval(waiter);
            resolve();
          }
        }, 100);
      });
    } else {
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
          if (code !== 0) {
            console.error(`Worker stopped with exit code ${code}`);
          }
        });
      }

      // Block and wait until the benchmark is stopped and all tasks are finished or cancelled
      await new Promise<void>(resolve => {
        const waiter = setInterval(() => {
          if (
            this.isStopped &&
            this.activeTasks === 0 &&
            this.taskQueue.length === 0
          ) {
            clearInterval(waiter);
            if (timeoutId) clearTimeout(timeoutId);
            console.log(
              'All outstanding active tasks completed. Benchmark run finished.',
            );
            resolve();
          }
        }, 100);
      });
    }

    this.printBenchmarkSummary(startWait);
  }

  protected printBenchmarkSummary(startTimeMs: number): void {
    const totalOps = this.successCount + this.errorCount;
    const elapsedSec = Math.max(0.001, (Date.now() - startTimeMs) / 1000);
    const actualTps = totalOps / elapsedSec;
    const stats = this.latencySampler.getStats();

    console.log('\n' + '='.repeat(60));
    console.log('                  BENCHMARK RUN SUMMARY');
    console.log('='.repeat(60));
    console.log(`Benchmark:       ${this.getName()}`);
    console.log(`Total Ops:       ${totalOps}`);
    console.log(
      totalOps > 0
        ? `Success Ops:     ${this.successCount} (${((100.0 * this.successCount) / totalOps).toFixed(1)}%)`
        : `Success Ops:     ${this.successCount}`,
    );
    console.log(
      totalOps > 0
        ? `Error Ops:       ${this.errorCount} (${((100.0 * this.errorCount) / totalOps).toFixed(1)}%)`
        : `Error Ops:       ${this.errorCount}`,
    );
    console.log(`Duration:        ${elapsedSec.toFixed(2)} s`);
    console.log(`Actual TPS:      ${actualTps.toFixed(2)} tps`);
    console.log('-'.repeat(60));
    if (stats) {
      console.log('Latency (microseconds):');
      console.log(`  Average:       ${stats.avg.toFixed(2)} us`);
      console.log(`  Min:           ${stats.min.toFixed(2)} us`);
      console.log(`  P50 (Median):  ${stats.p50.toFixed(2)} us`);
      console.log(`  P90:           ${stats.p90.toFixed(2)} us`);
      console.log(`  P95:           ${stats.p95.toFixed(2)} us`);
      console.log(`  P99:           ${stats.p99.toFixed(2)} us`);
      console.log(`  Max:           ${stats.max.toFixed(2)} us`);
    } else {
      console.log('No latency statistics collected.');
    }
    console.log('='.repeat(60) + '\n');
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
    if (this.activeTasks < this.threads) {
      this.runTask();
    } else {
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
        console.error(
          'Task dropped: workload queue is full (1M tasks exceeded)',
        );
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
      this.successCount++;
    } catch (err: any) {
      console.error(`Operation failed: ${err?.message || err}`);
      this.errorCounter.add(1, this.attributes);
      this.errorCount++;
    } finally {
      const endTimeNs = process.hrtime.bigint();
      if (this.shouldMeasureEntireMethod()) {
        const latencyUs = Number(endTimeNs - startTimeNs) / 1000;
        this.latencyHistogram.record(latencyUs, this.attributes);
        this.latencySampler.add(latencyUs);
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
      this.successCount++;
    } catch (err: any) {
      console.error(`Operation failed: ${err?.message || err}`);
      this.errorCounter.add(1, this.attributes);
      this.errorCount++;
    } finally {
      const endTimeNs = process.hrtime.bigint();
      if (this.shouldMeasureEntireMethod()) {
        const latencyUs = Number(endTimeNs - startTimeNs) / 1000;
        this.latencyHistogram.record(latencyUs, this.attributes);
        this.latencySampler.add(latencyUs);
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
