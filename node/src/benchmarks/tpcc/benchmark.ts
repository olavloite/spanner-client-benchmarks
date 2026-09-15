import {Database} from '@google-cloud/spanner';
import {Histogram, Counter} from '@opentelemetry/api';
import {AbstractBenchmark, LoadType} from '../abstract-benchmark';
import {BenchmarkWorkerData} from '../benchmark-worker';
import {executeTpccTransaction} from './transactions';

/**
 * TPC-C Benchmark Runner for Node.js Cloud Spanner Client.
 *
 * Architecture & Execution Model:
 * --------------------------------
 * TPC-C is a standard closed-loop online transaction processing (OLTP) benchmark simulating
 * a wholesale supplier environment across multiple warehouses, districts, and customers.
 *
 * Integration with AbstractBenchmark:
 * - Load Model: Runs with `LoadType.ClosedLoop`, where transactions are continuously issued
 *   to maintain a target concurrency of `clients` in-flight requests.
 * - Multi-Worker Scaling: When `workers > 1`, `AbstractBenchmark` creates a pool of Node.js
 *   `worker_threads` (each with an isolated V8 heap, libuv event loop, and dedicated Spanner client).
 *   The main coordinator thread seeds `clients` tasks across the worker pool and immediately
 *   dispatches a replacement task whenever a worker finishes a transaction.
 * - Dynamic Transaction Attributes: Unlike synthetic benchmarks with static query shapes,
 *   TPC-C transactions execute different business operations (`new_order`, `payment`, etc.).
 *   Workers execute each transaction and return its `txType` string to the coordinator,
 *   which records the execution latency into the corresponding OpenTelemetry metric attributes.
 */
export class TpccBenchmarkRunner extends AbstractBenchmark {
  private scaleFactor: number;
  private items: number;
  private extended: boolean;
  private txAttributes: Record<string, Record<string, any>>;
  private baseAttributes: Record<string, any>;

  constructor(
    database: Database,
    latencyHistogram: Histogram,
    operationCounter: Counter,
    errorCounter: Counter,
    memoryUsageHistogram: Histogram | null,
    cpuUtilizationHistogram: Histogram | null,
    resourceProbeIntervalStr: string,
    scaleFactor: number,
    clients: number,
    items: number,
    durationMs: number | null,
    forAlerting: boolean,
    benchmarkName: string,
    extended = false,
    workers = 1,
    host?: string,
  ) {
    // AbstractBenchmark constructor parameters explanation:
    // - tableName: 'warehouse' is passed as a nominal placeholder table name because TPC-C
    //   operates across 9 distinct relational tables (warehouse, district, customer, orders, etc.).
    // - minId / maxId: (1, 1) are unused placeholder values because TPC-C key ranges are
    //   dynamically generated per transaction according to warehouse and item distributions.
    // - tps: 0 is passed because arrival rates are self-paced by concurrency in closed-loop mode.
    // - threads: `clients` represents the closed-loop concurrency limit (number of in-flight requests).
    // - loadType: LoadType.ClosedLoop keeps `clients` tasks in flight without Poisson rate-limiting.
    // - Poisson/burst parameters (cycleDurationMs, peakFactor, burstFactor, etc.) are unused placeholders.
    super(
      database,
      latencyHistogram,
      operationCounter,
      errorCounter,
      memoryUsageHistogram,
      cpuUtilizationHistogram,
      resourceProbeIntervalStr,
      'warehouse', // tableName: nominal placeholder table name
      1, // minId: unused placeholder
      1, // maxId: unused placeholder
      0, // tps: unused in closed-loop mode
      clients, // threads: target in-flight concurrency (number of parallel clients)
      durationMs,
      forAlerting,
      benchmarkName,
      LoadType.ClosedLoop,
      null, // cycleDurationMs: unused in closed-loop mode
      2.0, // peakFactor: unused in closed-loop mode
      1.0, // burstFactor: unused in closed-loop mode
      1.0, // burstDuration: unused in closed-loop mode
      0.1, // burstFraction: unused in closed-loop mode
      false, // isMock
      workers, // workers: number of worker threads to distribute load across
      host,
    );
    this.scaleFactor = scaleFactor;
    this.items = items;
    this.extended = extended;

    this.baseAttributes = {
      benchmark_type: 'tpcc',
      for_alerting: forAlerting,
      benchmark_name: benchmarkName,
      client: 'node-client',
      concurrent_clients: clients,
    };
    if (extended) {
      this.baseAttributes.extended = true;
    }
    this.attributes = this.baseAttributes;

    this.txAttributes = {
      new_order: {...this.baseAttributes, transaction_type: 'new_order'},
      new_order_mutations: {
        ...this.baseAttributes,
        transaction_type: 'new_order_mutations',
      },
      payment: {...this.baseAttributes, transaction_type: 'payment'},
      payment_mutations_direct: {
        ...this.baseAttributes,
        transaction_type: 'payment_mutations_direct',
      },
      order_status: {...this.baseAttributes, transaction_type: 'order_status'},
      order_status_reads: {
        ...this.baseAttributes,
        transaction_type: 'order_status_reads',
      },
      delivery: {...this.baseAttributes, transaction_type: 'delivery'},
      stock_level: {...this.baseAttributes, transaction_type: 'stock_level'},
      stock_level_partitioned: {
        ...this.baseAttributes,
        transaction_type: 'stock_level_partitioned',
      },
    };
  }

  public getName(): string {
    return 'TPC-C Benchmark';
  }

  public getType(): string {
    return 'tpcc';
  }

  /**
   * Resolves OpenTelemetry metric attributes for a completed TPC-C task.
   * If the worker reported a specific transaction type (e.g. 'new_order', 'payment'),
   * returns the corresponding pre-computed attribute map; otherwise falls back to baseAttributes.
   */
  protected override getAttributesForTask(msg?: any): Record<string, any> {
    if (msg?.txType && this.txAttributes[msg.txType]) {
      return this.txAttributes[msg.txType];
    }
    return this.baseAttributes;
  }

  /**
   * Extends the base worker initialization payload with TPC-C specific parameters
   * (scaleFactor, items, and extended mode) transferred via structured cloning.
   */
  protected override getWorkerData(
    workerId: number,
    config: {
      projectId: string;
      instanceId: string;
      databaseId: string;
      host?: string;
      databaseOptions?: Record<string, any>;
    },
  ): BenchmarkWorkerData {
    return {
      ...super.getWorkerData(workerId, config),
      scaleFactor: this.scaleFactor,
      items: this.items,
      extended: this.extended,
    };
  }

  /**
   * Executes a single TPC-C transaction on the main thread (used when running with workers = 1).
   * In multi-worker mode (workers > 1), transactions are executed inside worker threads.
   */
  protected override async executeTask(
    database: Database,
    _tableName: string,
    _minId: number,
    _maxId: number,
  ): Promise<{txType: string}> {
    const txType = await executeTpccTransaction(
      database,
      this.scaleFactor,
      this.items,
      this.extended,
    );
    return {txType};
  }

  /**
   * Implements IBenchmark interface by delegating to executeTask.
   */
  public async execute(
    database: Database,
    tableName: string,
    minId: number,
    maxId: number,
  ): Promise<void> {
    await this.executeTask(database, tableName, minId, maxId);
  }

  /**
   * Verifies database capacity (checking that warehouse count >= scaleFactor)
   * before starting the benchmark execution loop.
   */
  public override async run(): Promise<void> {
    console.log(
      `Starting TPC-C Benchmark with Scale Factor (Warehouses): ${this.scaleFactor}, Parallel Clients: ${this.threads}, Items: ${this.items}${this.extended ? ' [EXTENDED MODE]' : ''}`,
    );

    // Assert database capacity
    const query = {sql: 'SELECT COUNT(*) AS cnt FROM warehouse'};
    const [rows] = await this.database.run(query);
    if (rows.length > 0) {
      const rowData = rows[0].toJSON();
      const warehouseCount = Number(rowData.cnt);
      if (warehouseCount < this.scaleFactor) {
        console.error(
          `Error: Database capacity check failed: Required scale factor ${this.scaleFactor} warehouses, but database only has ${warehouseCount}`,
        );
        process.exit(1);
      }
    }

    await super.run();
  }
}

export {TpccBenchmarkRunner as TpccBenchmark};
