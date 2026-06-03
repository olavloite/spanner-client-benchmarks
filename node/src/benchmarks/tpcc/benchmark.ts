import {Database} from '@google-cloud/spanner';
import {Histogram, Counter} from '@opentelemetry/api';
import {
  executeNewOrder,
  executePayment,
  executeOrderStatus,
  executeDelivery,
  executeStockLevel,
} from './transactions';
import {parseDuration} from '../../config/duration';

export class TpccBenchmarkRunner {
  private database: Database;
  private latencyHistogram: Histogram;
  private operationCounter: Counter;
  private errorCounter: Counter;
  private memoryUsageHistogram: Histogram | null;
  private cpuUtilizationHistogram: Histogram | null;
  private resourceProbeIntervalStr: string;
  private lastCpuUsage: NodeJS.CpuUsage | null = null;
  private lastWallTime = 0n;
  private resourceIntervalId: NodeJS.Timeout | null = null;
  private scaleFactor: number;
  private clients: number;
  private items: number;
  private durationMs: number | null;
  private attrNewOrder: Record<string, any>;
  private attrPayment: Record<string, any>;
  private attrOrderStatus: Record<string, any>;
  private attrDelivery: Record<string, any>;
  private attrStockLevel: Record<string, any>;
  private baseAttributes: Record<string, any>;
  private isStopped = false;

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
    benchmarkName: string
  ) {
    this.database = database;
    this.latencyHistogram = latencyHistogram;
    this.operationCounter = operationCounter;
    this.errorCounter = errorCounter;
    this.memoryUsageHistogram = memoryUsageHistogram;
    this.cpuUtilizationHistogram = cpuUtilizationHistogram;
    this.resourceProbeIntervalStr = resourceProbeIntervalStr;
    this.scaleFactor = scaleFactor;
    this.clients = clients;
    this.items = items;
    this.durationMs = durationMs;
    this.baseAttributes = {
      benchmark_type: 'tpcc',
      for_alerting: forAlerting,
      benchmark_name: benchmarkName,
      client: 'node-client',
      concurrent_clients: clients,
    };
    this.attrNewOrder = {...this.baseAttributes, transaction_type: 'new_order'};
    this.attrPayment = {...this.baseAttributes, transaction_type: 'payment'};
    this.attrOrderStatus = {
      ...this.baseAttributes,
      transaction_type: 'order_status',
    };
    this.attrDelivery = {...this.baseAttributes, transaction_type: 'delivery'};
    this.attrStockLevel = {
      ...this.baseAttributes,
      transaction_type: 'stock_level',
    };
  }

  public async run(): Promise<void> {
    console.log(
      `Starting TPC-C Benchmark with Scale Factor (Warehouses): ${this.scaleFactor}, Parallel Clients: ${this.clients}, Items: ${this.items}`
    );

    this.startResourceMonitoring();

    // Assert database capacity
    const query = {sql: 'SELECT COUNT(*) AS cnt FROM warehouse'};
    const [rows] = await this.database.run(query);
    if (rows.length > 0) {
      const rowData = rows[0].toJSON();
      const warehouseCount = Number(rowData.cnt);
      if (warehouseCount < this.scaleFactor) {
        console.error(
          `Error: Database capacity check failed: Required scale factor ${this.scaleFactor} warehouses, but database only has ${warehouseCount}`
        );
        process.exit(1);
      }
    }

    const startTime = Date.now();
    let timeoutId: NodeJS.Timeout | null = null;
    if (this.durationMs !== null) {
      timeoutId = setTimeout(() => {
        console.log('TPC-C duration complete. Shutting down pool...');
        this.stop();
      }, this.durationMs);
    }

    const promises: Promise<void>[] = [];
    for (let i = 0; i < this.clients; i++) {
      promises.push(this.workerLoop(startTime));
    }

    await Promise.all(promises);
    if (timeoutId) clearTimeout(timeoutId);
    console.log('TPC-C benchmark execution complete.');
  }

  private async workerLoop(startTime: number): Promise<void> {
    while (!this.isStopped) {
      if (
        this.durationMs !== null &&
        Date.now() - startTime >= this.durationMs
      ) {
        break;
      }

      const prob = Math.floor(Math.random() * 100);
      let txType = 'new_order';
      let attr = this.attrNewOrder;
      const opStartNs = process.hrtime.bigint();
      let success = false;

      try {
        if (prob < 45) {
          txType = 'new_order';
          attr = this.attrNewOrder;
          await executeNewOrder(this.database, this.scaleFactor, this.items);
        } else if (prob < 88) {
          txType = 'payment';
          attr = this.attrPayment;
          await executePayment(this.database, this.scaleFactor);
        } else if (prob < 92) {
          txType = 'order_status';
          attr = this.attrOrderStatus;
          await executeOrderStatus(this.database, this.scaleFactor);
        } else if (prob < 96) {
          txType = 'delivery';
          attr = this.attrDelivery;
          await executeDelivery(this.database, this.scaleFactor);
        } else {
          txType = 'stock_level';
          attr = this.attrStockLevel;
          await executeStockLevel(this.database, this.scaleFactor);
        }
        success = true;
      } catch (err: any) {
        console.error(
          `TPC-C transaction ${txType} failed: ${err?.message || err}`
        );
        this.errorCounter.add(1, attr);
      } finally {
        if (success) {
          const latencyUs = Number(process.hrtime.bigint() - opStartNs) / 1000;
          this.latencyHistogram.record(latencyUs, attr);
        }
        this.operationCounter.add(1, attr);
      }
    }
  }

  private startResourceMonitoring(): void {
    if (
      this.resourceProbeIntervalStr &&
      this.resourceProbeIntervalStr !== '0' &&
      this.resourceProbeIntervalStr !== '0s'
    ) {
      const intervalMs = parseDuration(this.resourceProbeIntervalStr);
      if (intervalMs !== null && intervalMs > 0) {
        this.lastCpuUsage = process.cpuUsage();
        this.lastWallTime = process.hrtime.bigint();
        this.resourceIntervalId = setInterval(
          () => this.probeResourceUsage(),
          intervalMs
        );
      }
    }
  }

  private probeResourceUsage(): void {
    if (this.isStopped) return;
    try {
      const mem = process.memoryUsage();
      if (this.memoryUsageHistogram) {
        this.memoryUsageHistogram.record(mem.heapUsed, this.baseAttributes);
      }

      if (this.lastCpuUsage && this.lastWallTime > 0n) {
        const nowCpuUsage = process.cpuUsage(this.lastCpuUsage);
        const nowWallTime = process.hrtime.bigint();
        const elapsedWallSec = Number(nowWallTime - this.lastWallTime) / 1e9;

        if (elapsedWallSec > 0 && this.cpuUtilizationHistogram) {
          const totalCpuSec = (nowCpuUsage.user + nowCpuUsage.system) / 1e6;
          const cpuUtil = totalCpuSec / elapsedWallSec;
          this.cpuUtilizationHistogram.record(cpuUtil, this.baseAttributes);
        }

        this.lastCpuUsage = process.cpuUsage();
        this.lastWallTime = nowWallTime;
      }
    } catch (e) {}
  }

  public stop(): void {
    this.isStopped = true;
    if (this.resourceIntervalId) {
      clearInterval(this.resourceIntervalId);
      this.resourceIntervalId = null;
    }
  }
}
