package main

import (
	"context"
	"database/sql"
	"time"

	"cloud.google.com/go/spanner"
	"go.opentelemetry.io/otel/metric"
)

type ctxKey string

const MetricAttributesKey ctxKey = "metric-attributes"

type ReadLargeResultSetBenchmark struct {
	numRows              int64
	readLatencyHistogram metric.Float64Histogram
	statementSql         string
}

func NewReadLargeResultSetBenchmark(readLatencyHistogram metric.Float64Histogram, numRows int64) *ReadLargeResultSetBenchmark {
	querySql := `SELECT
  MOD(FARM_FINGERPRINT(GENERATE_UUID()), 2) = 0 AS random_bool,
  CAST(GENERATE_UUID() AS BYTES) AS random_bytes,
  DATE_FROM_UNIX_DATE(ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 2932896))) AS random_date,
  CAST(FARM_FINGERPRINT(GENERATE_UUID()) / FARM_FINGERPRINT(GENERATE_UUID()) AS FLOAT32) AS random_float32,
  CAST(FARM_FINGERPRINT(GENERATE_UUID()) / FARM_FINGERPRINT(GENERATE_UUID()) AS FLOAT64) AS random_float64,
  -- CAST to STRING is used here because go-sql-spanner does not yet support native TypeCode_INTERVAL row decoding in database/sql.
  CAST(MAKE_INTERVAL(ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 10)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 12)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 28)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 24)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 60)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 60))) AS STRING) AS random_interval,
  TO_JSON('{"key": "' || GENERATE_UUID() || '"}') AS random_json,
  FARM_FINGERPRINT(GENERATE_UUID()) AS random_int64,
  CAST(FARM_FINGERPRINT(GENERATE_UUID()) / FARM_FINGERPRINT(GENERATE_UUID()) AS NUMERIC) AS random_numeric,
  GENERATE_UUID() AS random_string,
  TIMESTAMP_MICROS(ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 1230219000000000))) AS random_timestamp,
  NEW_UUID() AS random_uuid
FROM UNNEST(GENERATE_ARRAY(1, ?)) AS n`

	return &ReadLargeResultSetBenchmark{
		numRows:              numRows,
		readLatencyHistogram: readLatencyHistogram,
		statementSql:         querySql,
	}
}

func (benchmark *ReadLargeResultSetBenchmark) Name() string {
	return "Read Large Result Set Benchmark"
}

func (benchmark *ReadLargeResultSetBenchmark) Type() string {
	return "read-large-result-set"
}

// INTENTIONAL: Do not change ShouldMeasureEntireMethod to return true.
// We intentionally exclude the initial query execution and the first row fetch
// to measure purely the iteration and decoding latency of the remaining rows.
func (benchmark *ReadLargeResultSetBenchmark) ShouldMeasureEntireMethod() bool {
	return false
}

func (benchmark *ReadLargeResultSetBenchmark) Execute(ctx context.Context, connection *sql.Conn, tableName string, minId, maxId int64) error {
	rows, err := connection.QueryContext(ctx, benchmark.statementSql, benchmark.numRows)
	if err != nil {
		return err
	}
	defer rows.Close()

	if !rows.Next() {
		return rows.Err()
	}

	// Decode first row fully to native types
	if err := benchmark.decodeRow(rows); err != nil {
		return err
	}

	// Start timing iteration of remaining rows
	start := time.Now()
	for rows.Next() {
		if err := benchmark.decodeRow(rows); err != nil {
			return err
		}
	}
	if err := rows.Err(); err != nil {
		return err
	}
	durationMicroseconds := float64(time.Since(start).Microseconds())

	// Retrieve OTel attributes from context and record custom timing
	if attributesValue := ctx.Value(MetricAttributesKey); attributesValue != nil {
		if attributes, ok := attributesValue.(metric.MeasurementOption); ok {
			benchmark.readLatencyHistogram.Record(ctx, durationMicroseconds, attributes)
		}
	}

	return nil
}

func (benchmark *ReadLargeResultSetBenchmark) decodeRow(rows *sql.Rows) error {
	var randomBool bool
	var randomBytes []byte
	var randomDate spanner.NullDate
	var randomFloat32 float32
	var randomFloat64 float64
	var randomInterval string
	var randomJson spanner.NullJSON
	var randomInt64 int64
	var randomNumeric spanner.NullNumeric
	var randomString string
	var randomTimestamp time.Time
	var randomUuid string

	return rows.Scan(
		&randomBool,
		&randomBytes,
		&randomDate,
		&randomFloat32,
		&randomFloat64,
		&randomInterval,
		&randomJson,
		&randomInt64,
		&randomNumeric,
		&randomString,
		&randomTimestamp,
		&randomUuid,
	)
}
