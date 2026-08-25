package ycsb

import (
	"strings"
	"testing"
)

func TestGenerateSchemaDDL(t *testing.T) {
	ddl := GenerateSchemaDDL("usertable", 3)
	expectedPrefix := "CREATE TABLE IF NOT EXISTS usertable (\n    id STRING(MAX),\n    field0 STRING(MAX),\n    field1 STRING(MAX),\n    field2 STRING(MAX)\n) PRIMARY KEY(id)"
	if ddl != expectedPrefix {
		t.Fatalf("GenerateSchemaDDL mismatch:\nExpected:\n%s\nGot:\n%s", expectedPrefix, ddl)
	}

	if !strings.Contains(ddl, "CREATE TABLE IF NOT EXISTS usertable (") {
		t.Errorf("DDL should contain CREATE TABLE IF NOT EXISTS usertable")
	}
	if !strings.Contains(ddl, "PRIMARY KEY(id)") {
		t.Errorf("DDL should contain PRIMARY KEY(id)")
	}
}
