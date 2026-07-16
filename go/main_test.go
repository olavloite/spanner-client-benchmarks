package main

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/google/uuid"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/sdk/instrumentation"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
	"go.opentelemetry.io/otel/sdk/resource"
	spannerpb "google.golang.org/genproto/googleapis/spanner/v1"
)

func TestAllBenchmarksExecution(t *testing.T) {
	mockSrv, addr, cleanup := startMockServer()
	defer cleanup()

	os.Setenv("SPANNER_EMULATOR_HOST", addr)
	defer os.Unsetenv("SPANNER_EMULATOR_HOST")

	// Register mock statement results from helper file
	registerMockResults(mockSrv)

	tests := []struct {
		name string
		args []string
	}{
		{
			name: "point-select",
			args: []string{"benchmark-app", "--project=fake-project", "--instance=fake-instance", "--database=fake-database", "--table=test", "--duration=1s", "point-select", "--tps=100"},
		},
		{
			name: "select-update",
			args: []string{"benchmark-app", "--project=fake-project", "--instance=fake-instance", "--database=fake-database", "--table=test", "--duration=1s", "select-update", "--tps=100"},
		},
		{
			name: "read-large-result-set",
			args: []string{"benchmark-app", "--project=fake-project", "--instance=fake-instance", "--database=fake-database", "--duration=1s", "read-large-result-set", "--num-rows=10", "--tps=100"},
		},
		{
			name: "read-narrow-result-set",
			args: []string{"benchmark-app", "--project=fake-project", "--instance=fake-instance", "--database=fake-database", "--duration=1s", "read-narrow-result-set", "--num-rows=10", "--tps=100"},
		},
		{
			name: "tpcc",
			args: []string{"benchmark-app", "--project=fake-project", "--instance=fake-instance", "--database=fake-database", "--duration=1s", "tpcc", "--warehouses=1", "--clients=2", "--items=100"},
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			mockSrv.clearRequests()
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()

			err := run(ctx, tc.args)
			if err != nil {
				t.Fatalf("run benchmark %s failed: %v", tc.name, err)
			}

			// Verify at least one execute sql request was received
			requests := mockSrv.getRequests()
			receivedExecuteSql := false
			for _, req := range requests {
				if _, ok := req.(*spannerpb.ExecuteSqlRequest); ok {
					receivedExecuteSql = true
					break
				}
			}
			if !receivedExecuteSql {
				t.Errorf("benchmark %s did not trigger any ExecuteSqlRequest", tc.name)
			}
		})
	}
}

func TestMetricsCollection(t *testing.T) {
	mockSrv, addr, cleanup := startMockServer()
	defer cleanup()

	os.Setenv("SPANNER_EMULATOR_HOST", addr)
	defer os.Unsetenv("SPANNER_EMULATOR_HOST")

	registerMockResults(mockSrv)

	tests := []struct {
		name string
		args []string
	}{
		{
			name: "point-select",
			args: []string{"benchmark-app", "--project=fake-project", "--instance=fake-instance", "--database=fake-database", "--table=test", "--duration=1s", "--resource-probe-interval=10ms", "point-select", "--tps=100"},
		},
		{
			name: "select-update",
			args: []string{"benchmark-app", "--project=fake-project", "--instance=fake-instance", "--database=fake-database", "--table=test", "--duration=1s", "--resource-probe-interval=10ms", "select-update", "--tps=100"},
		},
		{
			name: "read-large-result-set",
			args: []string{"benchmark-app", "--project=fake-project", "--instance=fake-instance", "--database=fake-database", "--duration=1s", "--resource-probe-interval=10ms", "read-large-result-set", "--num-rows=10", "--tps=100"},
		},
		{
			name: "read-narrow-result-set",
			args: []string{"benchmark-app", "--project=fake-project", "--instance=fake-instance", "--database=fake-database", "--duration=1s", "--resource-probe-interval=10ms", "read-narrow-result-set", "--num-rows=10", "--tps=100"},
		},
		{
			name: "tpcc",
			args: []string{"benchmark-app", "--project=fake-project", "--instance=fake-instance", "--database=fake-database", "--duration=1s", "--resource-probe-interval=10ms", "tpcc", "--warehouses=1", "--clients=2", "--items=100"},
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			mockSrv.clearRequests()

			// Expose complete service name resource in test meter provider as well
			res, err := resource.Merge(
				resource.Default(),
				resource.NewWithAttributes(
					"",
					attribute.String("service.name", "spanner-benchmark"),
					attribute.String("service.instance.id", uuid.New().String()),
				),
			)
			if err != nil {
				t.Fatalf("failed to create resource: %v", err)
			}

			// Setup manual metric reader
			reader := sdkmetric.NewManualReader()
			mp := sdkmetric.NewMeterProvider(
				sdkmetric.WithResource(res),
				sdkmetric.WithReader(reader),
			)
			testingMeterProvider = mp
			defer func() {
				testingMeterProvider = nil
				_ = mp.Shutdown(context.Background())
			}()

			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()

			err = run(ctx, tc.args)
			if err != nil {
				t.Fatalf("failed to run benchmark: %v", err)
			}

			// Collect metrics
			var rm metricdata.ResourceMetrics
			err = reader.Collect(context.Background(), &rm)
			if err != nil {
				t.Fatalf("failed to collect metrics: %v", err)
			}

			// Verify that the service name resource attribute was correctly attached
			hasServiceName := false
			for _, attr := range rm.Resource.Set().ToSlice() {
				if attr.Key == attribute.Key("service.name") && attr.Value.AsString() == "spanner-benchmark" {
					hasServiceName = true
					break
				}
			}
			if !hasServiceName {
				t.Errorf("metrics resource is missing or has incorrect 'service.name' attribute")
			}

			expectedAttrs := map[string]string{
				"client":         "go-client",
				"benchmark_type": tc.name,
			}

			// Verify operation_count
			m, ok := findMetric(&rm, "spanner_client_benchmarks/operation_count")
			if !ok {
				t.Fatalf("operation count metric was not collected")
			}
			assertMetricAttributes(t, m, expectedAttrs)

			// Verify memory_usage
			m, ok = findMetric(&rm, "spanner_client_benchmarks/memory_usage")
			if !ok {
				t.Fatalf("memory usage metric was not collected")
			}
			assertMetricAttributes(t, m, expectedAttrs)

			// Verify cpu_utilization
			m, ok = findMetric(&rm, "spanner_client_benchmarks/cpu_utilization")
			if !ok {
				t.Fatalf("cpu utilization metric was not collected")
			}
			assertMetricAttributes(t, m, expectedAttrs)

			// Verify error_count (if collected, should be 0)
			if m, ok := findMetric(&rm, "spanner_client_benchmarks/error_count"); ok {
				assertMetricAttributes(t, m, expectedAttrs)
				switch data := m.Data.(type) {
				case metricdata.Sum[int64]:
					for _, dp := range data.DataPoints {
						if dp.Value != 0 {
							t.Errorf("expected 0 error count, but got: %d", dp.Value)
						}
					}
				default:
					t.Errorf("expected error count metric to be of type Sum[int64], got: %T", m.Data)
				}
			}
		})
	}
}

func assertMetricAttributes(t *testing.T, m metricdata.Metrics, expectedAttrs map[string]string) {
	switch data := m.Data.(type) {
	case metricdata.Sum[int64]:
		if len(data.DataPoints) == 0 {
			t.Errorf("metric %s has no data points", m.Name)
			return
		}
		for _, dp := range data.DataPoints {
			verifyAttributes(t, m.Name, dp.Attributes.ToSlice(), expectedAttrs)
		}
	case metricdata.Histogram[float64]:
		if len(data.DataPoints) == 0 {
			t.Errorf("metric %s has no data points", m.Name)
			return
		}
		for _, dp := range data.DataPoints {
			verifyAttributes(t, m.Name, dp.Attributes.ToSlice(), expectedAttrs)
		}
	default:
		t.Errorf("unsupported metric data type for %s: %T", m.Name, m.Data)
	}
}

func verifyAttributes(t *testing.T, name string, attrs []attribute.KeyValue, expected map[string]string) {
	for k, expectedVal := range expected {
		found := false
		for _, attr := range attrs {
			if string(attr.Key) == k {
				if attr.Value.AsString() == expectedVal {
					found = true
					break
				}
			}
		}
		if !found {
			t.Errorf("metric %s is missing expected attribute %s = %s in attributes %+v", name, k, expectedVal, attrs)
		}
	}
}

// Generic finder helper method to search for a metric by name in collected resource metrics
func findMetric(rm *metricdata.ResourceMetrics, name string) (metricdata.Metrics, bool) {
	for _, sm := range rm.ScopeMetrics {
		for _, m := range sm.Metrics {
			if m.Name == name {
				return m, true
			}
		}
	}
	return metricdata.Metrics{}, false
}

// Struct definition to satisfy Otel imports if needed
var _ instrumentation.Scope
