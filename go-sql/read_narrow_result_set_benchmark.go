package main

import (
	"context"
	"database/sql"
	"time"

	"go.opentelemetry.io/otel/metric"
)

type ReadNarrowResultSetBenchmark struct {
	numRows              int64
	readLatencyHistogram metric.Float64Histogram
	statementSql         string
}

func NewReadNarrowResultSetBenchmark(readLatencyHistogram metric.Float64Histogram, numRows int64) *ReadNarrowResultSetBenchmark {
	querySql := `SELECT
  FARM_FINGERPRINT(GENERATE_UUID()) AS random_int64_1,
  FARM_FINGERPRINT(GENERATE_UUID()) AS random_int64_2
FROM UNNEST(GENERATE_ARRAY(1, ?)) AS n`

	return &ReadNarrowResultSetBenchmark{
		numRows:              numRows,
		readLatencyHistogram: readLatencyHistogram,
		statementSql:         querySql,
	}
}

func (benchmark *ReadNarrowResultSetBenchmark) Name() string {
	return "Read Narrow Result Set Benchmark"
}

func (benchmark *ReadNarrowResultSetBenchmark) Type() string {
	return "read-narrow-result-set"
}

// INTENTIONAL: Do not change ShouldMeasureEntireMethod to return true.
// We intentionally exclude the initial query execution and the first row fetch
// to measure purely the iteration and decoding latency of the remaining rows.
func (benchmark *ReadNarrowResultSetBenchmark) ShouldMeasureEntireMethod() bool {
	return false
}

func (benchmark *ReadNarrowResultSetBenchmark) Execute(ctx context.Context, connection *sql.Conn, tableName string, minId, maxId int64) error {
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

func (benchmark *ReadNarrowResultSetBenchmark) decodeRow(rows *sql.Rows) error {
	var randomInt64One int64
	var randomInt64Two int64

	return rows.Scan(&randomInt64One, &randomInt64Two)
}
