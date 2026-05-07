import { Database } from "@google-cloud/spanner";
import { Histogram, Counter } from "@opentelemetry/api";
import { AbstractBenchmark } from "./abstract-benchmark";

const SQL = `SELECT
  MOD(FARM_FINGERPRINT(GENERATE_UUID()), 2) = 0 AS random_bool,
  CAST(GENERATE_UUID() AS BYTES) AS random_bytes,
  DATE_FROM_UNIX_DATE(ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 2932896))) AS random_date,
  CAST(FARM_FINGERPRINT(GENERATE_UUID()) / FARM_FINGERPRINT(GENERATE_UUID()) AS FLOAT32) AS random_float32,
  CAST(FARM_FINGERPRINT(GENERATE_UUID()) / FARM_FINGERPRINT(GENERATE_UUID()) AS FLOAT64) AS random_float64,
  MAKE_INTERVAL(ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 10)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 12)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 28)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 24)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 60)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 60))) AS random_interval,
  TO_JSON('{"key": "' || GENERATE_UUID() || '"}') AS random_json,
  FARM_FINGERPRINT(GENERATE_UUID()) AS random_int64,
  CAST(FARM_FINGERPRINT(GENERATE_UUID()) / FARM_FINGERPRINT(GENERATE_UUID()) AS NUMERIC) AS random_numeric,
  GENERATE_UUID() AS random_string,
  TIMESTAMP_MICROS(ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 1230219000000000))) AS random_timestamp,
  NEW_UUID() AS random_uuid
FROM UNNEST(GENERATE_ARRAY(1, @num_rows)) AS n`;

export class ReadLargeResultSetBenchmark extends AbstractBenchmark {
  private numRows: number;

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
    forAlerting: boolean,
    numRows: number
  ) {
    super(
      database,
      latencyHistogram,
      operationCounter,
      errorCounter,
      tableName,
      minId,
      maxId,
      tps,
      threads,
      durationMs,
      forAlerting
    );
    this.numRows = numRows;
  }

  public getName(): string {
    return "Read Large Result Set Benchmark";
  }

  public getType(): string {
    return "read-large-result-set";
  }

  protected shouldMeasureEntireMethod(): boolean {
    return false;
  }

  protected getAttributes(): Record<string, any> {
    const attrs = super.getAttributes();
    return {
      ...attrs,
      num_rows: this.numRows,
    };
  }

  public async execute(
    database: Database,
    tableName: string,
    minId: number,
    maxId: number
  ): Promise<void> {
    const query = {
      sql: SQL,
      params: {
        num_rows: this.numRows,
      },
      types: {
        num_rows: "int64",
      },
    };

    const stream = database.runStream(query);

    let firstRowDecoded = false;
    let startTimeNs = 0n;

    await new Promise<void>((resolve, reject) => {
      stream
        .on("data", (row) => {
          row.toJSON({ wrapNumbers: true });

          if (!firstRowDecoded) {
            firstRowDecoded = true;
            startTimeNs = process.hrtime.bigint();
          }
        })
        .on("end", () => {
          if (firstRowDecoded) {
            const endTimeNs = process.hrtime.bigint();
            const durationUs = Number(endTimeNs - startTimeNs) / 1000;
            this.latencyHistogram.record(durationUs, this.getAttributes());
          }
          resolve();
        })
        .on("error", (err) => {
          reject(err);
        });
    });
  }
}
