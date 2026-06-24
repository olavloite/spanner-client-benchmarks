package main

import (
	"context"
	"fmt"
	"net"
	"strings"
	"sync"

	spannerpb "google.golang.org/genproto/googleapis/spanner/v1"
	"google.golang.org/grpc"
	"google.golang.org/protobuf/types/known/emptypb"
	structpb "google.golang.org/protobuf/types/known/structpb"
)

type mockSpannerServer struct {
	spannerpb.UnimplementedSpannerServer
	mu               sync.Mutex
	requests         []interface{}
	statementResults map[string]*spannerpb.ResultSet
}

func newMockSpannerServer() *mockSpannerServer {
	return &mockSpannerServer{
		statementResults: make(map[string]*spannerpb.ResultSet),
	}
}

func (m *mockSpannerServer) putStatementResult(sql string, result *spannerpb.ResultSet) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.statementResults[sql] = result
}

func (m *mockSpannerServer) clearRequests() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.requests = nil
}

func (m *mockSpannerServer) getRequests() []interface{} {
	m.mu.Lock()
	defer m.mu.Unlock()
	return append([]interface{}{}, m.requests...)
}

func (m *mockSpannerServer) addRequest(req interface{}) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.requests = append(m.requests, req)
}

func (m *mockSpannerServer) CreateSession(ctx context.Context, req *spannerpb.CreateSessionRequest) (*spannerpb.Session, error) {
	m.addRequest(req)
	return &spannerpb.Session{
		Name: fmt.Sprintf("%s/sessions/session-123", req.Database),
	}, nil
}

func (m *mockSpannerServer) BatchCreateSessions(ctx context.Context, req *spannerpb.BatchCreateSessionsRequest) (*spannerpb.BatchCreateSessionsResponse, error) {
	m.addRequest(req)
	sessions := make([]*spannerpb.Session, req.SessionCount)
	for i := range sessions {
		sessions[i] = &spannerpb.Session{
			Name: fmt.Sprintf("%s/sessions/session-%d", req.Database, i),
		}
	}
	return &spannerpb.BatchCreateSessionsResponse{
		Session: sessions,
	}, nil
}

func (m *mockSpannerServer) DeleteSession(ctx context.Context, req *spannerpb.DeleteSessionRequest) (*emptypb.Empty, error) {
	m.addRequest(req)
	return &emptypb.Empty{}, nil
}

func (m *mockSpannerServer) BeginTransaction(ctx context.Context, req *spannerpb.BeginTransactionRequest) (*spannerpb.Transaction, error) {
	m.addRequest(req)
	return &spannerpb.Transaction{
		Id: []byte("tx-123"),
	}, nil
}

func (m *mockSpannerServer) Commit(ctx context.Context, req *spannerpb.CommitRequest) (*spannerpb.CommitResponse, error) {
	m.addRequest(req)
	return &spannerpb.CommitResponse{}, nil
}

func (m *mockSpannerServer) Rollback(ctx context.Context, req *spannerpb.RollbackRequest) (*emptypb.Empty, error) {
	m.addRequest(req)
	return &emptypb.Empty{}, nil
}

func (m *mockSpannerServer) ExecuteSql(ctx context.Context, req *spannerpb.ExecuteSqlRequest) (*spannerpb.ResultSet, error) {
	m.addRequest(req)
	m.mu.Lock()
	defer m.mu.Unlock()

	for sql, res := range m.statementResults {
		if strings.Contains(req.Sql, sql) {
			return res, nil
		}
	}

	// If DML statement (UPDATE/INSERT/DELETE), return successful ResultSet with row count stats
	sqlUpper := strings.ToUpper(strings.TrimSpace(req.Sql))
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

func (m *mockSpannerServer) ExecuteBatchDml(ctx context.Context, req *spannerpb.ExecuteBatchDmlRequest) (*spannerpb.ExecuteBatchDmlResponse, error) {
	m.addRequest(req)
	results := make([]*spannerpb.ResultSet, len(req.Statements))
	for i := range results {
		results[i] = &spannerpb.ResultSet{
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

func (m *mockSpannerServer) ExecuteStreamingSql(req *spannerpb.ExecuteSqlRequest, stream spannerpb.Spanner_ExecuteStreamingSqlServer) error {
	m.addRequest(req)
	m.mu.Lock()
	var matchedRes *spannerpb.ResultSet
	for sql, res := range m.statementResults {
		if strings.Contains(req.Sql, sql) {
			matchedRes = res
			break
		}
	}
	m.mu.Unlock()

	if matchedRes != nil {
		part := &spannerpb.PartialResultSet{
			Metadata: matchedRes.Metadata,
		}
		if len(matchedRes.Rows) > 0 {
			part.Values = matchedRes.Rows[0].Values
		}
		if err := stream.Send(part); err != nil {
			return err
		}

		for i := 1; i < len(matchedRes.Rows); i++ {
			part = &spannerpb.PartialResultSet{
				Values: matchedRes.Rows[i].Values,
			}
			if err := stream.Send(part); err != nil {
				return err
			}
		}
	} else {
		part := &spannerpb.PartialResultSet{}
		if err := stream.Send(part); err != nil {
			return err
		}
	}
	return nil
}

func startMockServer() (*mockSpannerServer, string, func()) {
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		panic(err)
	}
	s := grpc.NewServer()
	mockSrv := newMockSpannerServer()
	spannerpb.RegisterSpannerServer(s, mockSrv)

	go func() {
		_ = s.Serve(lis)
	}()

	cleanup := func() {
		s.Stop()
		_ = lis.Close()
	}
	return mockSrv, lis.Addr().String(), cleanup
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
					{Name: "random_json", Type: &spannerpb.Type{Code: spannerpb.TypeCode_STRING}},
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
		},
	}
}

func registerMockResults(m *mockSpannerServer) {
	m.putStatementResult("SELECT * FROM test WHERE id = @id", buildMockResultSet())
	m.putStatementResult("SELECT id FROM test WHERE id = @id", buildMockResultSet())
	m.putStatementResult("SELECT\n  MOD(FARM_FINGERPRINT", buildLargeMockResultSet())

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
	m.putStatementResult("SELECT COUNT(*) FROM warehouse", warehouseResult)
}
