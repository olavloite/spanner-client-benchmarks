package main

import (
	"context"
	"database/sql"
	"fmt"
	"math/rand"
	"sync"
	"time"

	"cloud.google.com/go/spanner"
	spannerdriver "github.com/googleapis/go-sql-spanner"
)

type PointSelectBenchmark struct {
	isMock          bool
	destinationPool sync.Pool
	queryOnce       sync.Once
	query           string
}

func (benchmark *PointSelectBenchmark) Name() string {
	return "Point Select Benchmark"
}

func (benchmark *PointSelectBenchmark) Type() string {
	return "point-select"
}

func (benchmark *PointSelectBenchmark) ShouldMeasureEntireMethod() bool {
	return true
}

func (benchmark *PointSelectBenchmark) Execute(ctx context.Context, connection *sql.Conn, tableName string, minId, maxId int64) error {
	var randomId int64
	if benchmark.isMock {
		randomId = 1
	} else {
		randomId = rand.Int63n(maxId-minId+1) + minId
	}
	benchmark.queryOnce.Do(func() {
		benchmark.query = fmt.Sprintf("SELECT * FROM %s WHERE id = ?", tableName)
	})

	rows, err := connection.QueryContext(ctx, benchmark.query, randomId)
	if err != nil {
		return err
	}
	defer rows.Close()

	columns, err := rows.Columns()
	if err != nil {
		return err
	}

	var destination []any
	if pooled := benchmark.destinationPool.Get(); pooled != nil {
		destination = pooled.([]any)
	}
	if len(destination) != len(columns) {
		destination = make([]any, len(columns))
		for index := range destination {
			destination[index] = new(any)
		}
	}
	defer benchmark.destinationPool.Put(destination)

	for rows.Next() {
		if err := rows.Scan(destination...); err != nil {
			return err
		}
	}
	return rows.Err()
}

func pointSelectConnectionInitializer(ctx context.Context, connection *sql.Conn) error {
	return connection.Raw(func(driverConnection interface{}) error {
		spannerConnection, ok := driverConnection.(spannerdriver.SpannerConn)
		if !ok {
			return fmt.Errorf("unexpected driver connection %T, expected SpannerConn", driverConnection)
		}
		return spannerConnection.SetReadOnlyStaleness(spanner.ExactStaleness(15 * time.Second))
	})
}
