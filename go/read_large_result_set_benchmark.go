package main

import (
	"context"
	"math/big"
	"time"

	"cloud.google.com/go/spanner"
	"go.opentelemetry.io/otel/metric"
	"google.golang.org/api/iterator"
)

type ctxKey string
const MetricAttributesKey ctxKey = "metric-attributes"

type ReadLargeResultSetBenchmark struct {
	numRows              int64
	readLatencyHistogram metric.Float64Histogram
	statement            spanner.Statement
}

func NewReadLargeResultSetBenchmark(readLatencyHistogram metric.Float64Histogram, numRows int64) *ReadLargeResultSetBenchmark {
	sql := `SELECT
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
FROM UNNEST(GENERATE_ARRAY(1, @num_rows)) AS n`

	return &ReadLargeResultSetBenchmark{
		numRows:              numRows,
		readLatencyHistogram: readLatencyHistogram,
		statement: spanner.Statement{
			SQL:    sql,
			Params: map[string]interface{}{"num_rows": numRows},
		},
	}
}

func (b *ReadLargeResultSetBenchmark) Name() string {
	return "Read Large Result Set Benchmark"
}

func (b *ReadLargeResultSetBenchmark) Type() string {
	return "read-large-result-set"
}

// INTENTIONAL: Do not change ShouldMeasureEntireMethod to return true.
// We intentionally exclude the initial query execution and the first row fetch
// to measure purely the iteration and decoding latency of the remaining rows.
func (b *ReadLargeResultSetBenchmark) ShouldMeasureEntireMethod() bool {
	return false
}

func (b *ReadLargeResultSetBenchmark) Execute(ctx context.Context, client *spanner.Client, tableName string, minId, maxId int64) error {
	iter := client.Single().Query(ctx, b.statement)
	defer iter.Stop()

	row, err := iter.Next()
	if err == iterator.Done {
		return nil
	}
	if err != nil {
		return err
	}

	// Decode first row fully to native types
	b.decodeRow(row)

	// Start timing iteration of remaining rows
	start := time.Now()
	for {
		row, err = iter.Next()
		if err == iterator.Done {
			break
		}
		if err != nil {
			return err
		}
		b.decodeRow(row)
	}
	durationUs := float64(time.Since(start).Microseconds())

	// Retrieve OTel attributes from context and record custom timing
	if attributesVal := ctx.Value(MetricAttributesKey); attributesVal != nil {
		if attrs, ok := attributesVal.(metric.MeasurementOption); ok {
			b.readLatencyHistogram.Record(ctx, durationUs, attrs)
		}
	}

	return nil
}

func (b *ReadLargeResultSetBenchmark) decodeRow(row *spanner.Row) {
	var randomBool bool
	var randomBytes []byte
	var randomDate spanner.NullDate
	var randomFloat32 float32
	var randomFloat64 float64
	var randomInterval spanner.GenericColumnValue
	var randomJson spanner.NullJSON
	var randomInt64 int64
	var randomNumeric big.Rat
	var randomString string
	var randomTimestamp time.Time
	var randomUuid string

	row.Column(0, &randomBool)
	row.Column(1, &randomBytes)
	row.Column(2, &randomDate)
	row.Column(3, &randomFloat32)
	row.Column(4, &randomFloat64)
	row.Column(5, &randomInterval)
	row.Column(6, &randomJson)
	row.Column(7, &randomInt64)
	row.Column(8, &randomNumeric)
	row.Column(9, &randomString)
	row.Column(10, &randomTimestamp)
	row.Column(11, &randomUuid)
}
