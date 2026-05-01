package main

import (
	"context"
	"os"
	"testing"
	"time"

	"cloud.google.com/go/spanner/spannertest"
	"cloud.google.com/go/spanner/spansql"
)

func TestBenchmarkExecution(t *testing.T) {
	srv, err := spannertest.NewServer("localhost:0")
	if err != nil {
		t.Fatalf("failed to start in-memory Spanner server: %v", err)
	}
	defer srv.Close()

	os.Setenv("SPANNER_EMULATOR_HOST", srv.Addr)
	defer os.Unsetenv("SPANNER_EMULATOR_HOST")

	// Create dummy table
	ddl, err := spansql.ParseDDL("-", "CREATE TABLE test (id INT64, value STRING(MAX)) PRIMARY KEY(id);")
	if err != nil {
		t.Fatalf("failed to parse DDL: %v", err)
	}
	if err := srv.UpdateDDL(ddl); err != nil {
		t.Fatalf("failed to apply DDL: %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	args := []string{"-project=fake-project", "-instance=fake-instance", "-database=fake-database", "-table=test", "--duration=2s", "point-select"}
	err = run(ctx, args)
	if err != nil {
		t.Fatalf("run benchmark failed: %v", err)
	}
	
	// Success means no panics happened
	t.Log("Execution successfully completed.")
}
