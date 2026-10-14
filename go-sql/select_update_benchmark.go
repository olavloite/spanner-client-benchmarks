package main

import (
	"context"
	"database/sql"
	"fmt"
	"math/rand"
	"sync"
)

type SelectAndUpdateBenchmark struct {
	queryOnce sync.Once
	selectSql string
	updateSql string
	insertSql string
}

func (benchmark *SelectAndUpdateBenchmark) Name() string {
	return "Select and Update Benchmark"
}

func (benchmark *SelectAndUpdateBenchmark) Type() string {
	return "select-update"
}

func (benchmark *SelectAndUpdateBenchmark) ShouldMeasureEntireMethod() bool {
	return true
}

func (benchmark *SelectAndUpdateBenchmark) Execute(ctx context.Context, connection *sql.Conn, tableName string, minId, maxId int64) error {
	randomId := rand.Int63n(maxId-minId+1) + minId

	benchmark.queryOnce.Do(func() {
		benchmark.selectSql = fmt.Sprintf("SELECT id FROM %s WHERE id = ?", tableName)
		benchmark.updateSql = fmt.Sprintf("UPDATE %s SET value = ? WHERE id = ?", tableName)
		benchmark.insertSql = fmt.Sprintf("INSERT INTO %s (id, value) VALUES (?, ?)", tableName)
	})

	transaction, err := connection.BeginTx(ctx, &sql.TxOptions{})
	if err != nil {
		return err
	}
	defer func() {
		if transaction != nil {
			_ = transaction.Rollback()
		}
	}()

	rows, err := transaction.QueryContext(ctx, benchmark.selectSql, randomId)
	if err != nil {
		return err
	}
	exists := false
	if rows.Next() {
		exists = true
	}
	_ = rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}

	randomValue := generateRandomString(rand.Intn(76) + 75) // 75 to 150 chars

	if exists {
		if _, err := transaction.ExecContext(ctx, benchmark.updateSql, randomValue, randomId); err != nil {
			return err
		}
	} else {
		if _, err := transaction.ExecContext(ctx, benchmark.insertSql, randomId, randomValue); err != nil {
			return err
		}
	}

	err = transaction.Commit()
	if err != nil {
		return err
	}
	transaction = nil
	return nil
}

func generateRandomString(length int) string {
	characters := "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
	bytes := make([]byte, length)
	for index := range bytes {
		bytes[index] = characters[rand.Intn(len(characters))]
	}
	return string(bytes)
}
