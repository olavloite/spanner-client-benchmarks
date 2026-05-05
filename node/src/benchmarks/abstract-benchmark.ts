import { Database } from "@google-cloud/spanner";
import { Histogram, Counter } from "@opentelemetry/api";

export interface IBenchmark {
  execute(database: Database, tableName: string, minId: number, maxId: number): Promise<void>;
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

  private attributes: Record<string, any>;
  private activeTasks = 0;
  private taskQueue: number[] = [];
  private isStopped = false;

  constructor(
    database: Database,
    latencyHistogram: Histogram,
    operationCounter: Counter,
    errorCounter: Counter,
    tableName: string,
    minId: number,
    maxId: number,
    tps: number,
    threads: number,
    durationMs: number | null,
    forAlerting: boolean
  ) {
    this.database = database;
    this.latencyHistogram = latencyHistogram;
    this.operationCounter = operationCounter;
    this.errorCounter = errorCounter;
    this.tableName = tableName;
    this.minId = minId;
    this.maxId = maxId;
    this.tps = tps;
    this.threads = threads;
    this.durationMs = durationMs;
    this.forAlerting = forAlerting;

    // Pre-create attributes to avoid object creation overhead on the hot path (parity with Go and Java)
    this.attributes = {
      benchmark_type: this.getType(),
      tps: this.tps,
      for_alerting: this.forAlerting,
      client: "node-client",
    };
  }

  abstract execute(database: Database, tableName: string, minId: number, maxId: number): Promise<void>;
  abstract getName(): string;
  abstract getType(): string;

  /**
   * Runs the workload generator loop until the duration is reached or stop() is called.
   */
  public async run(): Promise<void> {
    console.log(`Starting ${this.getName()}`);
    console.log(`Parameters: TPS=${this.tps}, Max Workers=${this.threads}, MinID=${this.minId}, MaxID=${this.maxId}`);

    const startTimeNs = process.hrtime.bigint();
    let nextTaskTimeNs = startTimeNs;

    let timeoutId: NodeJS.Timeout | null = null;
    if (this.durationMs !== null) {
      timeoutId = setTimeout(() => {
        console.log("Benchmark duration reached. Stopping workload generator...");
        this.stop();
      }, this.durationMs);
    }

    // High-precision recursive setImmediate scheduler loop
    const scheduleLoop = () => {
      if (this.isStopped) {
        return;
      }

      const nowNs = process.hrtime.bigint();

      // Self-healing guard: if the process falls behind by more than 1 second (e.g. thread block/suspend),
      // snap nextTaskTimeNs forward to prevent extreme memory spikes and infinite catch-up loops.
      if (nowNs - nextTaskTimeNs > 1000000000n) {
        console.warn("Scheduler fell behind by >1s. Resetting workload timeline to prevent OOM.");
        nextTaskTimeNs = nowNs;
      }

      // Spawn all operations whose scheduled trigger time has arrived or passed
      while (nowNs >= nextTaskTimeNs && !this.isStopped) {
        this.submitTask();

        // Calculate next arrival inter-event interval using Poisson process distribution
        const delayNs = this.calculatePoissonDelayNs(this.tps);
        nextTaskTimeNs += delayNs;
      }

      // Yield event loop slice or sleep depending on remaining time
      if (!this.isStopped) {
        const nextNowNs = process.hrtime.bigint();
        const remainingNs = nextTaskTimeNs - nextNowNs;

        if (remainingNs > 1000000n) { // More than 1ms remaining
          const remainingMs = Number(remainingNs / 1000000n);
          setTimeout(scheduleLoop, remainingMs);
        } else {
          setImmediate(scheduleLoop);
        }
      }
    };

    // Kick off the asynchronous scheduler
    setImmediate(scheduleLoop);

    // Block and wait until the benchmark is stopped and all tasks are finished or cancelled
    return new Promise<void>((resolve) => {
      const waiter = setInterval(() => {
        if (this.isStopped && this.activeTasks === 0 && this.taskQueue.length === 0) {
          clearInterval(waiter);
          if (timeoutId) clearTimeout(timeoutId);
          console.log("All outstanding active tasks completed. Benchmark run finished.");
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
  }

  /**
   * Pushes a task into active execution if concurrency allows, otherwise queues it.
   */
  private submitTask(): void {
    if (this.activeTasks < this.threads) {
      this.runTask();
    } else {
      if (this.taskQueue.length < 1000000) {
        this.taskQueue.push(1);
      } else {
        // Task queue is full, drop task to simulate unbounded network queue limits (parity with Go's 1M limit)
        console.error("Task dropped: workload queue is full (1M tasks exceeded)");
      }
    }
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
      const latencyUs = Number(endTimeNs - startTimeNs) / 1000;
      
      this.latencyHistogram.record(latencyUs, this.attributes);
      this.operationCounter.add(1, this.attributes);
      this.activeTasks--;

      // Drain buffered queue slots concurrently as workers become available
      if (this.taskQueue.length > 0 && this.activeTasks < this.threads && !this.isStopped) {
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
    const u = Math.random();
    // Prevent u being exactly 1.0 which would result in ln(0) -> -Infinity
    const safeU = u === 1.0 ? 0.999999999 : u;
    const delaySeconds = -Math.log(1.0 - safeU) / rate;
    return BigInt(Math.floor(delaySeconds * 1_000_000_000));
  }
}
