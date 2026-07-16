package main

import (
	"context"
	"time"

	"cloud.google.com/go/spanner"
	"go.opentelemetry.io/otel/metric"
	"google.golang.org/api/iterator"
)

type ReadNarrowResultSetBenchmark struct {
	numRows              int64
	readLatencyHistogram metric.Float64Histogram
	statement            spanner.Statement
}

func NewReadNarrowResultSetBenchmark(readLatencyHistogram metric.Float64Histogram, numRows int64) *ReadNarrowResultSetBenchmark {
	sql := `SELECT
  FARM_FINGERPRINT(GENERATE_UUID()) AS random_int64_1,
  FARM_FINGERPRINT(GENERATE_UUID()) AS random_int64_2
FROM UNNEST(GENERATE_ARRAY(1, @num_rows)) AS n`

	return &ReadNarrowResultSetBenchmark{
		numRows:              numRows,
		readLatencyHistogram: readLatencyHistogram,
		statement: spanner.Statement{
			SQL:    sql,
			Params: map[string]interface{}{"num_rows": numRows},
		},
	}
}

func (b *ReadNarrowResultSetBenchmark) Name() string {
	return "Read Narrow Result Set Benchmark"
}

func (b *ReadNarrowResultSetBenchmark) Type() string {
	return "read-narrow-result-set"
}

// INTENTIONAL: Do not change ShouldMeasureEntireMethod to return true.
// We intentionally exclude the initial query execution and the first row fetch
// to measure purely the iteration and decoding latency of the remaining rows.
func (b *ReadNarrowResultSetBenchmark) ShouldMeasureEntireMethod() bool {
	return false
}

func (b *ReadNarrowResultSetBenchmark) Execute(ctx context.Context, client *spanner.Client, tableName string, minId, maxId int64) error {
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

func (b *ReadNarrowResultSetBenchmark) decodeRow(row *spanner.Row) {
	var randomInt64_1 int64
	var randomInt64_2 int64

	row.Column(0, &randomInt64_1)
	row.Column(1, &randomInt64_2)
}
