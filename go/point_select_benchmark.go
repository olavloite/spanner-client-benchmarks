package main

import (
	"context"
	"fmt"
	"math/rand"

	"cloud.google.com/go/spanner"
	"google.golang.org/api/iterator"
)

type PointSelectBenchmark struct{}

func (b *PointSelectBenchmark) Name() string { return "Point Select Benchmark" }
func (b *PointSelectBenchmark) Type() string { return "point-select" }
func (b *PointSelectBenchmark) Execute(ctx context.Context, client *spanner.Client, tableName string, minId, maxId int64) error {
	randomId := rand.Int63n(maxId-minId+1) + minId
	sql := fmt.Sprintf("SELECT * FROM %s WHERE id = @id", tableName)

	iter := client.Single().Query(ctx, spanner.Statement{
		SQL:    sql,
		Params: map[string]interface{}{"id": randomId},
	})
	defer iter.Stop()

	for {
		row, err := iter.Next()
		if err == iterator.Done {
			break
		}
		if err != nil {
			return err
		}
		var id int64
		row.Column(0, &id) // read first column
	}
	return nil
}
