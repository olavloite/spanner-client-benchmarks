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
	mockServer, address, cleanup := startMockServer()
	defer cleanup()

	os.Setenv("SPANNER_EMULATOR_HOST", address)
	defer os.Unsetenv("SPANNER_EMULATOR_HOST")

	// Register mock statement results from helper file
	registerMockResults(mockServer)

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
		{
			name: "tpcc-extended",
			args: []string{"benchmark-app", "--project=fake-project", "--instance=fake-instance", "--database=fake-database", "--duration=1s", "tpcc", "--warehouses=1", "--clients=2", "--items=100", "--extended"},
		},
	}

	for _, testCase := range tests {
		t.Run(testCase.name, func(t *testing.T) {
			mockServer.clearRequests()
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()

			err := run(ctx, testCase.args)
			if err != nil {
				t.Fatalf("run benchmark %s failed: %v", testCase.name, err)
			}

			// Verify at least one execute sql request was received
			requests := mockServer.getRequests()
			receivedExecuteSql := false
			for _, request := range requests {
				if _, ok := request.(*spannerpb.ExecuteSqlRequest); ok {
					receivedExecuteSql = true
					break
				}
			}
			if !receivedExecuteSql {
				t.Errorf("benchmark %s did not trigger any ExecuteSqlRequest", testCase.name)
			}
		})
	}
}

func TestMetricsCollection(t *testing.T) {
	mockServer, address, cleanup := startMockServer()
	defer cleanup()

	os.Setenv("SPANNER_EMULATOR_HOST", address)
	defer os.Unsetenv("SPANNER_EMULATOR_HOST")

	registerMockResults(mockServer)

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
		{
			name: "tpcc-extended",
			args: []string{"benchmark-app", "--project=fake-project", "--instance=fake-instance", "--database=fake-database", "--duration=1s", "--resource-probe-interval=10ms", "tpcc", "--warehouses=1", "--clients=2", "--items=100", "--extended"},
		},
	}

	for _, testCase := range tests {
		t.Run(testCase.name, func(t *testing.T) {
			mockServer.clearRequests()

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
			meterProvider := sdkmetric.NewMeterProvider(
				sdkmetric.WithResource(res),
				sdkmetric.WithReader(reader),
			)
			testingMeterProvider = meterProvider
			defer func() {
				testingMeterProvider = nil
				_ = meterProvider.Shutdown(context.Background())
			}()

			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()

			err = run(ctx, testCase.args)
			if err != nil {
				t.Fatalf("failed to run benchmark: %v", err)
			}

			// Collect metrics
			var resourceMetrics metricdata.ResourceMetrics
			err = reader.Collect(context.Background(), &resourceMetrics)
			if err != nil {
				t.Fatalf("failed to collect metrics: %v", err)
			}

			// Verify that the service name resource attribute was correctly attached
			hasServiceName := false
			for _, attributeEntry := range resourceMetrics.Resource.Set().ToSlice() {
				if attributeEntry.Key == attribute.Key("service.name") && attributeEntry.Value.AsString() == "spanner-benchmark" {
					hasServiceName = true
					break
				}
			}
			if !hasServiceName {
				t.Errorf("metrics resource is missing or has incorrect 'service.name' attribute")
			}

			expectedBenchmarkType := testCase.name
			if testCase.name == "tpcc-extended" {
				expectedBenchmarkType = "tpcc"
			}
			expectedAttributes := map[string]string{
				"client":         "go-sql",
				"benchmark_type": expectedBenchmarkType,
			}

			// Verify operation_count
			metricItem, found := findMetric(&resourceMetrics, "spanner_client_benchmarks/operation_count")
			if !found {
				t.Fatalf("operation count metric was not collected")
			}
			assertMetricAttributes(t, metricItem, expectedAttributes)

			// Verify memory_usage
			metricItem, found = findMetric(&resourceMetrics, "spanner_client_benchmarks/memory_usage")
			if !found {
				t.Fatalf("memory usage metric was not collected")
			}
			assertMetricAttributes(t, metricItem, expectedAttributes)

			// Verify cpu_utilization
			metricItem, found = findMetric(&resourceMetrics, "spanner_client_benchmarks/cpu_utilization")
			if !found {
				t.Fatalf("cpu utilization metric was not collected")
			}
			assertMetricAttributes(t, metricItem, expectedAttributes)

			// Verify error_count (if collected, should be 0)
			if metricItem, found := findMetric(&resourceMetrics, "spanner_client_benchmarks/error_count"); found {
				assertMetricAttributes(t, metricItem, expectedAttributes)
				switch data := metricItem.Data.(type) {
				case metricdata.Sum[int64]:
					for _, dataPoint := range data.DataPoints {
						if dataPoint.Value != 0 {
							t.Errorf("expected 0 error count, but got: %d", dataPoint.Value)
						}
					}
				default:
					t.Errorf("expected error count metric to be of type Sum[int64], got: %T", metricItem.Data)
				}
			}
		})
	}
}

func assertMetricAttributes(t *testing.T, metricItem metricdata.Metrics, expectedAttributes map[string]string) {
	switch data := metricItem.Data.(type) {
	case metricdata.Sum[int64]:
		if len(data.DataPoints) == 0 {
			t.Errorf("metric %s has no data points", metricItem.Name)
			return
		}
		for _, dataPoint := range data.DataPoints {
			verifyAttributes(t, metricItem.Name, dataPoint.Attributes.ToSlice(), expectedAttributes)
		}
	case metricdata.Histogram[float64]:
		if len(data.DataPoints) == 0 {
			t.Errorf("metric %s has no data points", metricItem.Name)
			return
		}
		for _, dataPoint := range data.DataPoints {
			verifyAttributes(t, metricItem.Name, dataPoint.Attributes.ToSlice(), expectedAttributes)
		}
	default:
		t.Errorf("unsupported metric data type for %s: %T", metricItem.Name, metricItem.Data)
	}
}

func verifyAttributes(t *testing.T, name string, attributes []attribute.KeyValue, expected map[string]string) {
	for expectedKey, expectedValue := range expected {
		found := false
		for _, attributeItem := range attributes {
			if string(attributeItem.Key) == expectedKey {
				if attributeItem.Value.AsString() == expectedValue {
					found = true
					break
				}
			}
		}
		if !found {
			t.Errorf("metric %s is missing expected attribute %s = %s in attributes %+v", name, expectedKey, expectedValue, attributes)
		}
	}
}

// Generic finder helper method to search for a metric by name in collected resource metrics
func findMetric(resourceMetrics *metricdata.ResourceMetrics, name string) (metricdata.Metrics, bool) {
	for _, scopeMetrics := range resourceMetrics.ScopeMetrics {
		for _, metricItem := range scopeMetrics.Metrics {
			if metricItem.Name == name {
				return metricItem, true
			}
		}
	}
	return metricdata.Metrics{}, false
}

// Struct definition to satisfy Otel imports if needed
var _ instrumentation.Scope
