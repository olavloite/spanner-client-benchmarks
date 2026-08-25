package ycsb

import (
	"context"
	"fmt"
	"strings"

	"cloud.google.com/go/spanner"
	databaseadmin "cloud.google.com/go/spanner/admin/database/apiv1"
	"google.golang.org/api/iterator"
	"google.golang.org/api/option"
	adminpb "google.golang.org/genproto/googleapis/spanner/admin/database/v1"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

// GenerateSchemaDDL generates the DDL statements for creating the YCSB table in Cloud Spanner.
func GenerateSchemaDDL(tableName string, fieldCount int) string {
	var fieldDefs []string
	for i := 0; i < fieldCount; i++ {
		fieldDefs = append(fieldDefs, fmt.Sprintf("    field%d STRING(MAX)", i))
	}

	return fmt.Sprintf("CREATE TABLE IF NOT EXISTS %s (\n    id STRING(MAX),\n%s\n) PRIMARY KEY(id)", tableName, strings.Join(fieldDefs, ",\n"))
}

// TableExists checks if the specified table already exists in the Spanner database.
func TableExists(ctx context.Context, client *spanner.Client, tableName string) (bool, error) {
	stmt := spanner.Statement{
		SQL:    "SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '' AND TABLE_NAME = @tableName",
		Params: map[string]interface{}{"tableName": tableName},
	}
	iter := client.Single().Query(ctx, stmt)
	defer iter.Stop()

	_, err := iter.Next()
	if err == iterator.Done {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return true, nil
}

// InitSchema executes DDL update statements using the Cloud Spanner DatabaseAdmin client.
func InitSchema(ctx context.Context, project, instance, database, host string, ddlStatements []string) error {
	var clientOpts []option.ClientOption
	if host != "" {
		clientOpts = append(clientOpts, option.WithEndpoint(host), option.WithGRPCDialOption(grpc.WithTransportCredentials(insecure.NewCredentials())), option.WithoutAuthentication())
	}

	adminClient, err := databaseadmin.NewDatabaseAdminClient(ctx, clientOpts...)
	if err != nil {
		return fmt.Errorf("failed to create database admin client: %w", err)
	}
	defer adminClient.Close()

	databaseName := fmt.Sprintf("projects/%s/instances/%s/databases/%s", project, instance, database)
	op, err := adminClient.UpdateDatabaseDdl(ctx, &adminpb.UpdateDatabaseDdlRequest{
		Database:   databaseName,
		Statements: ddlStatements,
	})
	if err != nil {
		return fmt.Errorf("failed to initiate update database DDL: %w", err)
	}

	if err := op.Wait(ctx); err != nil {
		return fmt.Errorf("failed waiting for DDL operation to complete: %w", err)
	}

	return nil
}
