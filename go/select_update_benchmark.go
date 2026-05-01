package main

import (
	"context"
	"fmt"
	"math/rand"

	"cloud.google.com/go/spanner"
)

type SelectAndUpdateBenchmark struct{}

func (b *SelectAndUpdateBenchmark) Name() string { return "Select and Update Benchmark" }
func (b *SelectAndUpdateBenchmark) Type() string { return "select-update" }
func (b *SelectAndUpdateBenchmark) Execute(ctx context.Context, client *spanner.Client, tableName string, minId, maxId int64) error {
	randomId := rand.Int63n(maxId-minId+1) + minId

	_, err := client.ReadWriteTransaction(ctx, func(ctx context.Context, txn *spanner.ReadWriteTransaction) error {
		sql := fmt.Sprintf("SELECT id FROM %s WHERE id = @id", tableName)
		iter := txn.Query(ctx, spanner.Statement{
			SQL:    sql,
			Params: map[string]interface{}{"id": randomId},
		})
		defer iter.Stop()

		exists := false
		if _, err := iter.Next(); err == nil {
			exists = true
		}

		randomValue := generateRandomString(rand.Intn(76) + 75) // 75 to 150 chars

		if exists {
			updateSql := fmt.Sprintf("UPDATE %s SET value = @value WHERE id = @id", tableName)
			_, err := txn.Update(ctx, spanner.Statement{
				SQL:    updateSql,
				Params: map[string]interface{}{"value": randomValue, "id": randomId},
			})
			return err
		} else {
			insertSql := fmt.Sprintf("INSERT INTO %s (id, value) VALUES (@id, @value)", tableName)
			_, err := txn.Update(ctx, spanner.Statement{
				SQL:    insertSql,
				Params: map[string]interface{}{"value": randomValue, "id": randomId},
			})
			return err
		}
	})
	return err
}

func generateRandomString(length int) string {
	chars := "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
	b := make([]byte, length)
	for i := range b {
		b[i] = chars[rand.Intn(len(chars))]
	}
	return string(b)
}
