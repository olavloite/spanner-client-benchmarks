import {Database, MutationSet} from '@google-cloud/spanner';
import {Histogram, Counter} from '@opentelemetry/api';
import {AbstractBenchmark, LoadType} from '../abstract-benchmark';
import {
  ZipfianGenerator,
  ScrambledZipfianGenerator,
  UniformIntegerGenerator,
  SkewedLatestGenerator,
} from './generator';
import {buildKeyName, buildRow, generateRandomString} from './utils';
import {
  Workload,
  KeyDistribution,
  Operation,
  chooseOperation,
} from './workload';

export class YcsbBenchmark extends AbstractBenchmark {
  public static blackholeSink = 0;

  private ycsbWorkload: Workload;
  private distribution: KeyDistribution;
  private recordCount: number;
  private zeroPadding: number;
  private fieldCount: number;
  private fieldLength: number;
  private useReadRow: boolean;
  private isMockServer: boolean;

  private zipfianGenerator: ZipfianGenerator;
  private scrambledZipfianGenerator: ScrambledZipfianGenerator;
  private uniformGenerator: UniformIntegerGenerator;
  private skewedLatestGenerator: SkewedLatestGenerator;
  private insertKeySequence: {value: bigint};

  private fieldNames: string[];
  private readSql: string;
  private scanSql: string;

  // Operation Metrics
  private readDurationNs = 0n;
  private readCount = 0;
  private updateDurationNs = 0n;
  private updateCount = 0;
  private insertDurationNs = 0n;
  private insertCount = 0;
  private scanDurationNs = 0n;
  private scanCount = 0;
  private rmwDurationNs = 0n;
  private rmwCount = 0;

  constructor(
    database: Database,
    latencyHistogram: Histogram,
    operationCounter: Counter,
    errorCounter: Counter,
    memoryUsageHistogram: Histogram | null,
    cpuUtilizationHistogram: Histogram | null,
    resourceProbeIntervalStr: string,
    tableName: string,
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
    workload: Workload = Workload.B,
    distribution: KeyDistribution = KeyDistribution.ScrambledZipfian,
    recordCount = 100000,
    zeroPadding = 12,
    fieldCount = 10,
    fieldLength = 100,
    useReadRow = false,
  ) {
    super(
      database,
      latencyHistogram,
      operationCounter,
      errorCounter,
      memoryUsageHistogram,
      cpuUtilizationHistogram,
      resourceProbeIntervalStr,
      tableName,
      0,
      recordCount - 1,
      tps,
      threads,
      durationMs,
      forAlerting,
      benchmarkName,
      loadType,
      cycleDurationMs,
      peakFactor,
      burstFactor,
      burstDuration,
      burstFraction,
      isMock,
    );

    this.ycsbWorkload = workload;
    this.distribution = distribution;
    this.recordCount = recordCount > 0 ? recordCount : 100000;
    this.zeroPadding = zeroPadding >= 0 ? zeroPadding : 12;
    this.fieldCount = fieldCount > 0 ? fieldCount : 10;
    this.fieldLength = fieldLength > 0 ? fieldLength : 100;
    this.useReadRow = useReadRow;
    this.isMockServer = isMock;

    this.attributes['workload'] = this.ycsbWorkload;
    this.attributes['transaction_type'] =
      `ycsb-${this.ycsbWorkload.toLowerCase()}`;

    this.fieldNames = [];
    for (let i = 0; i < this.fieldCount; i++) {
      this.fieldNames.push(`field${i}`);
    }

    this.readSql = `SELECT ${this.fieldNames.join(', ')} FROM ${this.tableName} WHERE id = @id`;
    this.scanSql = `SELECT ${this.fieldNames.join(', ')} FROM ${this.tableName} WHERE id >= @startKey ORDER BY id LIMIT @scanLength`;

    this.insertKeySequence = {value: BigInt(this.recordCount)};
    this.zipfianGenerator = new ZipfianGenerator(
      0n,
      BigInt(this.recordCount - 1),
    );
    this.scrambledZipfianGenerator = new ScrambledZipfianGenerator(
      0n,
      BigInt(this.recordCount - 1),
    );
    this.uniformGenerator = new UniformIntegerGenerator(
      0n,
      BigInt(this.recordCount - 1),
    );
    this.skewedLatestGenerator = new SkewedLatestGenerator(
      this.insertKeySequence,
    );
  }

  public getName(): string {
    return `YCSB Benchmark (${this.ycsbWorkload})`;
  }

  public getType(): string {
    return 'ycsb';
  }

  public async execute(
    database: Database,
    tableName: string,
    minId: number,
    maxId: number,
  ): Promise<void> {
    const op = chooseOperation(this.ycsbWorkload);
    switch (op) {
      case Operation.Read:
        await this.executeRead(database, tableName);
        break;
      case Operation.Update:
        await this.executeUpdate(database, tableName);
        break;
      case Operation.Insert:
        await this.executeInsert(database, tableName);
        break;
      case Operation.Scan:
        await this.executeScan(database, tableName);
        break;
      case Operation.ReadModifyWrite:
        await this.executeReadModifyWrite(database, tableName);
        break;
    }
  }

  public override async run(): Promise<void> {
    await super.run();
    this.printSummary();
  }

  public printSummary(): void {
    if (this.readCount > 0) {
      const avgMs = Number(this.readDurationNs) / 1_000_000 / this.readCount;
      console.log(
        `  [READ]   Count: ${this.readCount.toLocaleString()} ops, Avg Latency: ${avgMs.toFixed(2)} ms`,
      );
    }
    if (this.updateCount > 0) {
      const avgMs =
        Number(this.updateDurationNs) / 1_000_000 / this.updateCount;
      console.log(
        `  [UPDATE] Count: ${this.updateCount.toLocaleString()} ops, Avg Latency: ${avgMs.toFixed(2)} ms`,
      );
    }
    if (this.insertCount > 0) {
      const avgMs =
        Number(this.insertDurationNs) / 1_000_000 / this.insertCount;
      console.log(
        `  [INSERT] Count: ${this.insertCount.toLocaleString()} ops, Avg Latency: ${avgMs.toFixed(2)} ms`,
      );
    }
    if (this.scanCount > 0) {
      const avgMs = Number(this.scanDurationNs) / 1_000_000 / this.scanCount;
      console.log(
        `  [SCAN]   Count: ${this.scanCount.toLocaleString()} ops, Avg Latency: ${avgMs.toFixed(2)} ms`,
      );
    }
    if (this.rmwCount > 0) {
      const avgMs = Number(this.rmwDurationNs) / 1_000_000 / this.rmwCount;
      console.log(
        `  [RMW]    Count: ${this.rmwCount.toLocaleString()} ops, Avg Latency: ${avgMs.toFixed(2)} ms`,
      );
    }
  }

  private static consumeRows(rows: any[], fieldNames: string[]): void {
    if (!rows) return;
    let totalLength = 0;
    for (const row of rows) {
      if (!row) continue;
      for (let i = 0; i < fieldNames.length; i++) {
        const val = row[fieldNames[i]];
        if (typeof val === 'string') {
          totalLength += val.length;
        }
      }
    }
    YcsbBenchmark.blackholeSink += totalLength;
  }

  private getRandomKey(): string {
    if (this.isMockServer) {
      return buildKeyName(0, this.zeroPadding);
    }
    switch (this.distribution) {
      case KeyDistribution.ScrambledZipfian:
        return buildKeyName(
          this.scrambledZipfianGenerator.nextLong(),
          this.zeroPadding,
        );
      case KeyDistribution.Zipfian:
        return buildKeyName(this.zipfianGenerator.nextLong(), this.zeroPadding);
      case KeyDistribution.Uniform:
        return buildKeyName(this.uniformGenerator.nextLong(), this.zeroPadding);
      default:
        return buildKeyName(
          this.scrambledZipfianGenerator.nextLong(),
          this.zeroPadding,
        );
    }
  }

  private async executeRead(
    database: Database,
    tableName: string,
  ): Promise<void> {
    const start = process.hrtime.bigint();
    let key: string;
    if (this.ycsbWorkload === Workload.D && !this.isMockServer) {
      key = buildKeyName(
        this.skewedLatestGenerator.nextLong(),
        this.zeroPadding,
      );
    } else {
      key = this.getRandomKey();
    }

    if (this.useReadRow) {
      const [rows] = await database.table(tableName).read({
        keys: [key],
        columns: this.fieldNames,
        json: true,
      });
      if (!rows || rows.length === 0) {
        throw new Error(`Row not found for key: ${key}`);
      }
      YcsbBenchmark.consumeRows(rows, this.fieldNames);
    } else {
      const [rows] = await database.run({
        sql: this.readSql,
        params: {id: key},
        types: {id: 'string'},
      });
      if (!rows || rows.length === 0) {
        throw new Error(`Row not found for key: ${key}`);
      }
      YcsbBenchmark.consumeRows(rows, this.fieldNames);
    }

    const duration = process.hrtime.bigint() - start;
    this.readDurationNs += duration;
    this.readCount++;
  }

  private async executeUpdate(
    database: Database,
    tableName: string,
  ): Promise<void> {
    const start = process.hrtime.bigint();
    const key = this.getRandomKey();
    const fieldIndex = Math.floor(Math.random() * this.fieldCount);
    const fieldName = this.fieldNames[fieldIndex];
    const value = generateRandomString(this.fieldLength);

    const mutations = new MutationSet();
    mutations.upsert(tableName, [{id: key, [fieldName]: value}]);
    await database.writeAtLeastOnce(mutations);

    const duration = process.hrtime.bigint() - start;
    this.updateDurationNs += duration;
    this.updateCount++;
  }

  private async executeInsert(
    database: Database,
    tableName: string,
  ): Promise<void> {
    const start = process.hrtime.bigint();
    let recordNumber = 0n;
    if (!this.isMockServer) {
      recordNumber = this.insertKeySequence.value;
      this.insertKeySequence.value++;
    }
    const key = buildKeyName(recordNumber, this.zeroPadding);
    const row = buildRow(key, this.fieldCount, this.fieldLength);

    const mutations = new MutationSet();
    mutations.upsert(tableName, [row]);
    await database.writeAtLeastOnce(mutations);

    const duration = process.hrtime.bigint() - start;
    this.insertDurationNs += duration;
    this.insertCount++;
  }

  private async executeScan(
    database: Database,
    tableName: string,
  ): Promise<void> {
    const start = process.hrtime.bigint();
    const startKey = this.getRandomKey();
    const scanLength = this.isMockServer
      ? 10
      : Math.floor(Math.random() * 100) + 1;

    if (this.useReadRow) {
      const [rows] = await database.table(tableName).read({
        ranges: [
          {
            startClosed: [startKey],
          },
        ],
        columns: this.fieldNames,
        limit: scanLength,
        json: true,
      });
      YcsbBenchmark.consumeRows(rows, this.fieldNames);
    } else {
      const [rows] = await database.run({
        sql: this.scanSql,
        params: {startKey, scanLength},
        types: {startKey: 'string', scanLength: 'int64'},
      });
      YcsbBenchmark.consumeRows(rows, this.fieldNames);
    }

    const duration = process.hrtime.bigint() - start;
    this.scanDurationNs += duration;
    this.scanCount++;
  }

  private async executeReadModifyWrite(
    database: Database,
    tableName: string,
  ): Promise<void> {
    const start = process.hrtime.bigint();
    const key = this.getRandomKey();
    const fieldIndex = Math.floor(Math.random() * this.fieldCount);
    const fieldName = this.fieldNames[fieldIndex];
    const value = generateRandomString(this.fieldLength);

    await database.runTransactionAsync(async transaction => {
      const [rows] = await transaction.read(tableName, {
        keys: [key],
        columns: this.fieldNames,
        json: true,
      });
      if (!rows || rows.length === 0) {
        throw new Error(`Row not found for key: ${key}`);
      }
      YcsbBenchmark.consumeRows(rows, this.fieldNames);
      await transaction.upsert(tableName, [{id: key, [fieldName]: value}]);
      await transaction.commit();
    });

    const duration = process.hrtime.bigint() - start;
    this.rmwDurationNs += duration;
    this.rmwCount++;
  }
}
