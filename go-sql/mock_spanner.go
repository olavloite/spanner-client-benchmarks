package main

import (
	"context"
	"fmt"
	"net"
	"strings"
	"sync"

	spannerpb "google.golang.org/genproto/googleapis/spanner/v1"
	"google.golang.org/grpc"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/emptypb"
	structpb "google.golang.org/protobuf/types/known/structpb"
	"google.golang.org/protobuf/types/known/timestamppb"
)

type mockSpannerServer struct {
	spannerpb.UnimplementedSpannerServer
	mutex            sync.Mutex
	requests         []interface{}
	statementResults map[string]*spannerpb.ResultSet
}

func newMockSpannerServer() *mockSpannerServer {
	return &mockSpannerServer{
		statementResults: make(map[string]*spannerpb.ResultSet),
	}
}

func (server *mockSpannerServer) putStatementResult(sql string, result *spannerpb.ResultSet) {
	server.mutex.Lock()
	defer server.mutex.Unlock()
	server.statementResults[sql] = result
}

func (server *mockSpannerServer) clearRequests() {
	server.mutex.Lock()
	defer server.mutex.Unlock()
	server.requests = nil
}

func (server *mockSpannerServer) getRequests() []interface{} {
	server.mutex.Lock()
	defer server.mutex.Unlock()
	return append([]interface{}{}, server.requests...)
}

func (server *mockSpannerServer) addRequest(request interface{}) {
	server.mutex.Lock()
	defer server.mutex.Unlock()
	server.requests = append(server.requests, request)
}

func (server *mockSpannerServer) CreateSession(ctx context.Context, request *spannerpb.CreateSessionRequest) (*spannerpb.Session, error) {
	server.addRequest(request)
	return &spannerpb.Session{
		Name: fmt.Sprintf("%s/sessions/session-123", request.Database),
	}, nil
}

func (server *mockSpannerServer) BatchCreateSessions(ctx context.Context, request *spannerpb.BatchCreateSessionsRequest) (*spannerpb.BatchCreateSessionsResponse, error) {
	server.addRequest(request)
	sessions := make([]*spannerpb.Session, request.SessionCount)
	for index := range sessions {
		sessions[index] = &spannerpb.Session{
			Name: fmt.Sprintf("%s/sessions/session-%d", request.Database, index),
		}
	}
	return &spannerpb.BatchCreateSessionsResponse{
		Session: sessions,
	}, nil
}

func (server *mockSpannerServer) DeleteSession(ctx context.Context, request *spannerpb.DeleteSessionRequest) (*emptypb.Empty, error) {
	server.addRequest(request)
	return &emptypb.Empty{}, nil
}

func (server *mockSpannerServer) BeginTransaction(ctx context.Context, request *spannerpb.BeginTransactionRequest) (*spannerpb.Transaction, error) {
	server.addRequest(request)
	return &spannerpb.Transaction{
		Id: []byte("tx-123"),
	}, nil
}

func (server *mockSpannerServer) Commit(ctx context.Context, request *spannerpb.CommitRequest) (*spannerpb.CommitResponse, error) {
	server.addRequest(request)
	return &spannerpb.CommitResponse{
		CommitTimestamp: timestamppb.Now(),
	}, nil
}

func (server *mockSpannerServer) Rollback(ctx context.Context, request *spannerpb.RollbackRequest) (*emptypb.Empty, error) {
	server.addRequest(request)
	return &emptypb.Empty{}, nil
}

func (server *mockSpannerServer) ExecuteSql(ctx context.Context, request *spannerpb.ExecuteSqlRequest) (*spannerpb.ResultSet, error) {
	server.addRequest(request)
	server.mutex.Lock()
	defer server.mutex.Unlock()

	for sql, result := range server.statementResults {
		if strings.Contains(request.Sql, sql) {
			return result, nil
		}
	}

	// If DML statement (UPDATE/INSERT/DELETE), return successful ResultSet with row count stats
	sqlUpper := strings.ToUpper(strings.TrimSpace(request.Sql))
	if strings.HasPrefix(sqlUpper, "UPDATE") || strings.HasPrefix(sqlUpper, "INSERT") || strings.HasPrefix(sqlUpper, "DELETE") {
		return &spannerpb.ResultSet{
			Stats: &spannerpb.ResultSetStats{
				RowCount: &spannerpb.ResultSetStats_RowCountExact{
					RowCountExact: 1,
				},
			},
		}, nil
	}

	return &spannerpb.ResultSet{}, nil
}

func (server *mockSpannerServer) ExecuteBatchDml(ctx context.Context, request *spannerpb.ExecuteBatchDmlRequest) (*spannerpb.ExecuteBatchDmlResponse, error) {
	server.addRequest(request)
	results := make([]*spannerpb.ResultSet, len(request.Statements))
	for index := range results {
		results[index] = &spannerpb.ResultSet{
			Stats: &spannerpb.ResultSetStats{
				RowCount: &spannerpb.ResultSetStats_RowCountExact{
					RowCountExact: 1,
				},
			},
		}
	}
	return &spannerpb.ExecuteBatchDmlResponse{
		ResultSets: results,
	}, nil
}

func (server *mockSpannerServer) ExecuteStreamingSql(request *spannerpb.ExecuteSqlRequest, stream spannerpb.Spanner_ExecuteStreamingSqlServer) error {
	server.addRequest(request)
	server.mutex.Lock()
	var matchedResult *spannerpb.ResultSet
	for sql, result := range server.statementResults {
		if strings.Contains(request.Sql, sql) {
			matchedResult = result
			break
		}
	}
	server.mutex.Unlock()

	if matchedResult != nil {
		var metadata *spannerpb.ResultSetMetadata
		if matchedResult.Metadata != nil {
			metadata = proto.Clone(matchedResult.Metadata).(*spannerpb.ResultSetMetadata)
		} else {
			metadata = &spannerpb.ResultSetMetadata{
				RowType: &spannerpb.StructType{},
			}
		}
		if request.Transaction != nil && request.Transaction.GetBegin() != nil {
			metadata.Transaction = &spannerpb.Transaction{
				Id: []byte("mock-transaction-id"),
			}
		}
		part := &spannerpb.PartialResultSet{
			Metadata: metadata,
		}
		if len(matchedResult.Rows) > 0 {
			part.Values = matchedResult.Rows[0].Values
		}
		if err := stream.Send(part); err != nil {
			return err
		}

		for index := 1; index < len(matchedResult.Rows); index++ {
			part = &spannerpb.PartialResultSet{
				Values: matchedResult.Rows[index].Values,
			}
			if err := stream.Send(part); err != nil {
				return err
			}
		}
	} else {
		metadata := &spannerpb.ResultSetMetadata{
			RowType: &spannerpb.StructType{},
		}
		if request.Transaction != nil && request.Transaction.GetBegin() != nil {
			metadata.Transaction = &spannerpb.Transaction{
				Id: []byte("mock-transaction-id"),
			}
		}
		part := &spannerpb.PartialResultSet{
			Metadata: metadata,
			Stats: &spannerpb.ResultSetStats{
				RowCount: &spannerpb.ResultSetStats_RowCountExact{
					RowCountExact: 1,
				},
			},
		}
		if err := stream.Send(part); err != nil {
			return err
		}
	}
	return nil
}

func (server *mockSpannerServer) StreamingRead(request *spannerpb.ReadRequest, stream spannerpb.Spanner_StreamingReadServer) error {
	server.addRequest(request)
	var fields []*spannerpb.StructType_Field
	var values []*structpb.Value
	for _, col := range request.Columns {
		switch col {
		case "balance", "amount":
			fields = append(fields, &spannerpb.StructType_Field{
				Name: col,
				Type: &spannerpb.Type{Code: spannerpb.TypeCode_FLOAT64},
			})
			values = append(values, &structpb.Value{Kind: &structpb.Value_NumberValue{NumberValue: 50.0}})
		case "first_name", "last_name":
			fields = append(fields, &spannerpb.StructType_Field{
				Name: col,
				Type: &spannerpb.Type{Code: spannerpb.TypeCode_STRING},
			})
			values = append(values, &structpb.Value{Kind: &structpb.Value_StringValue{StringValue: "test"}})
		default:
			fields = append(fields, &spannerpb.StructType_Field{
				Name: col,
				Type: &spannerpb.Type{Code: spannerpb.TypeCode_INT64},
			})
			values = append(values, &structpb.Value{Kind: &structpb.Value_StringValue{StringValue: "1"}})
		}
	}
	if len(fields) == 0 {
		fields = append(fields, &spannerpb.StructType_Field{
			Name: "col",
			Type: &spannerpb.Type{Code: spannerpb.TypeCode_INT64},
		})
		values = append(values, &structpb.Value{Kind: &structpb.Value_StringValue{StringValue: "1"}})
	}

	metadata := &spannerpb.ResultSetMetadata{
		RowType: &spannerpb.StructType{
			Fields: fields,
		},
	}
	if request.Transaction != nil && request.Transaction.GetBegin() != nil {
		metadata.Transaction = &spannerpb.Transaction{
			Id: []byte("mock-transaction-id"),
		}
	}
	part := &spannerpb.PartialResultSet{
		Metadata: metadata,
		Values:   values,
	}
	return stream.Send(part)
}

func (server *mockSpannerServer) PartitionQuery(ctx context.Context, request *spannerpb.PartitionQueryRequest) (*spannerpb.PartitionResponse, error) {
	server.addRequest(request)
	return &spannerpb.PartitionResponse{
		Partitions: []*spannerpb.Partition{
			{PartitionToken: []byte("mock-partition-token")},
		},
	}, nil
}

func startMockServer() (*mockSpannerServer, string, func()) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		panic(err)
	}
	grpcServer := grpc.NewServer()
	mockServer := newMockSpannerServer()
	spannerpb.RegisterSpannerServer(grpcServer, mockServer)

	go func() {
		_ = grpcServer.Serve(listener)
	}()

	cleanup := func() {
		grpcServer.Stop()
		_ = listener.Close()
	}
	return mockServer, listener.Addr().String(), cleanup
}

func buildMockResultSet() *spannerpb.ResultSet {
	return &spannerpb.ResultSet{
		Metadata: &spannerpb.ResultSetMetadata{
			RowType: &spannerpb.StructType{
				Fields: []*spannerpb.StructType_Field{
					{Name: "id", Type: &spannerpb.Type{Code: spannerpb.TypeCode_INT64}},
					{Name: "value", Type: &spannerpb.Type{Code: spannerpb.TypeCode_STRING}},
				},
			},
		},
		Rows: []*structpb.ListValue{
			{
				Values: []*structpb.Value{
					{Kind: &structpb.Value_StringValue{StringValue: "1"}},
					{Kind: &structpb.Value_StringValue{StringValue: "test-value"}},
				},
			},
		},
	}
}

func buildLargeMockResultSet() *spannerpb.ResultSet {
	return &spannerpb.ResultSet{
		Metadata: &spannerpb.ResultSetMetadata{
			RowType: &spannerpb.StructType{
				Fields: []*spannerpb.StructType_Field{
					{Name: "random_bool", Type: &spannerpb.Type{Code: spannerpb.TypeCode_BOOL}},
					{Name: "random_bytes", Type: &spannerpb.Type{Code: spannerpb.TypeCode_BYTES}},
					{Name: "random_date", Type: &spannerpb.Type{Code: spannerpb.TypeCode_DATE}},
					{Name: "random_float32", Type: &spannerpb.Type{Code: spannerpb.TypeCode_FLOAT64}},
					{Name: "random_float64", Type: &spannerpb.Type{Code: spannerpb.TypeCode_FLOAT64}},
					{Name: "random_interval", Type: &spannerpb.Type{Code: spannerpb.TypeCode_STRING}},
					{Name: "random_json", Type: &spannerpb.Type{Code: spannerpb.TypeCode_JSON}},
					{Name: "random_int64", Type: &spannerpb.Type{Code: spannerpb.TypeCode_INT64}},
					{Name: "random_numeric", Type: &spannerpb.Type{Code: spannerpb.TypeCode_NUMERIC}},
					{Name: "random_string", Type: &spannerpb.Type{Code: spannerpb.TypeCode_STRING}},
					{Name: "random_timestamp", Type: &spannerpb.Type{Code: spannerpb.TypeCode_TIMESTAMP}},
					{Name: "random_uuid", Type: &spannerpb.Type{Code: spannerpb.TypeCode_STRING}},
				},
			},
		},
		Rows: []*structpb.ListValue{
			{
				Values: []*structpb.Value{
					{Kind: &structpb.Value_BoolValue{BoolValue: true}},
					{Kind: &structpb.Value_StringValue{StringValue: "YWJj"}},
					{Kind: &structpb.Value_StringValue{StringValue: "2026-06-02"}},
					{Kind: &structpb.Value_NumberValue{NumberValue: 1.23}},
					{Kind: &structpb.Value_NumberValue{NumberValue: 4.56}},
					{Kind: &structpb.Value_StringValue{StringValue: "0-0 0 0:0:0"}},
					{Kind: &structpb.Value_StringValue{StringValue: "{\"key\":\"val\"}"}},
					{Kind: &structpb.Value_StringValue{StringValue: "100"}},
					{Kind: &structpb.Value_StringValue{StringValue: "12.34"}},
					{Kind: &structpb.Value_StringValue{StringValue: "hello"}},
					{Kind: &structpb.Value_StringValue{StringValue: "2026-06-02T13:43:09Z"}},
					{Kind: &structpb.Value_StringValue{StringValue: "00000000-0000-0000-0000-000000000000"}},
				},
			},
			{
				Values: []*structpb.Value{
					{Kind: &structpb.Value_BoolValue{BoolValue: false}},
					{Kind: &structpb.Value_StringValue{StringValue: "ZGVm"}},
					{Kind: &structpb.Value_StringValue{StringValue: "2026-06-03"}},
					{Kind: &structpb.Value_NumberValue{NumberValue: 7.89}},
					{Kind: &structpb.Value_NumberValue{NumberValue: 0.12}},
					{Kind: &structpb.Value_StringValue{StringValue: "1-0 0 0:0:0"}},
					{Kind: &structpb.Value_StringValue{StringValue: "{\"key\":\"val2\"}"}},
					{Kind: &structpb.Value_StringValue{StringValue: "200"}},
					{Kind: &structpb.Value_StringValue{StringValue: "56.78"}},
					{Kind: &structpb.Value_StringValue{StringValue: "world"}},
					{Kind: &structpb.Value_StringValue{StringValue: "2026-06-03T14:43:09Z"}},
					{Kind: &structpb.Value_StringValue{StringValue: "11111111-1111-1111-1111-111111111111"}},
				},
			},
		},
	}
}

func buildNarrowMockResultSet() *spannerpb.ResultSet {
	return &spannerpb.ResultSet{
		Metadata: &spannerpb.ResultSetMetadata{
			RowType: &spannerpb.StructType{
				Fields: []*spannerpb.StructType_Field{
					{Name: "random_int64_1", Type: &spannerpb.Type{Code: spannerpb.TypeCode_INT64}},
					{Name: "random_int64_2", Type: &spannerpb.Type{Code: spannerpb.TypeCode_INT64}},
				},
			},
		},
		Rows: []*structpb.ListValue{
			{
				Values: []*structpb.Value{
					{Kind: &structpb.Value_StringValue{StringValue: "100"}},
					{Kind: &structpb.Value_StringValue{StringValue: "200"}},
				},
			},
			{
				Values: []*structpb.Value{
					{Kind: &structpb.Value_StringValue{StringValue: "300"}},
					{Kind: &structpb.Value_StringValue{StringValue: "400"}},
				},
			},
		},
	}
}

func registerMockResults(server *mockSpannerServer) {
	dialectResult := &spannerpb.ResultSet{
		Metadata: &spannerpb.ResultSetMetadata{
			RowType: &spannerpb.StructType{
				Fields: []*spannerpb.StructType_Field{
					{Name: "option_value", Type: &spannerpb.Type{Code: spannerpb.TypeCode_STRING}},
				},
			},
		},
		Rows: []*structpb.ListValue{
			{
				Values: []*structpb.Value{
					{Kind: &structpb.Value_StringValue{StringValue: "GOOGLE_STANDARD_SQL"}},
				},
			},
		},
	}
	server.putStatementResult("database_options where option_name='database_dialect'", dialectResult)

	server.putStatementResult("SELECT * FROM test", buildMockResultSet())
	server.putStatementResult("SELECT id FROM test", buildMockResultSet())
	server.putStatementResult("AS random_bool", buildLargeMockResultSet())
	server.putStatementResult("AS random_int64_1", buildNarrowMockResultSet())

	warehouseResult := &spannerpb.ResultSet{
		Metadata: &spannerpb.ResultSetMetadata{
			RowType: &spannerpb.StructType{
				Fields: []*spannerpb.StructType_Field{
					{Name: "count", Type: &spannerpb.Type{Code: spannerpb.TypeCode_INT64}},
				},
			},
		},
		Rows: []*structpb.ListValue{
			{
				Values: []*structpb.Value{
					{Kind: &structpb.Value_StringValue{StringValue: "1"}},
				},
			},
		},
	}
	server.putStatementResult("SELECT COUNT(*) FROM warehouse", warehouseResult)
}
