package ycsb

import (
	"math"
	"testing"
)

func TestWorkloadDistributions(t *testing.T) {
	const iterations = 50000

	testCases := []struct {
		workload       Workload
		expectedRead   float64
		expectedUpdate float64
		expectedInsert float64
		expectedScan   float64
		expectedRMW    float64
	}{
		{WorkloadA, 0.50, 0.50, 0.00, 0.00, 0.00},
		{WorkloadB, 0.95, 0.05, 0.00, 0.00, 0.00},
		{WorkloadC, 1.00, 0.00, 0.00, 0.00, 0.00},
		{WorkloadD, 0.95, 0.00, 0.05, 0.00, 0.00},
		{WorkloadE, 0.00, 0.00, 0.05, 0.95, 0.00},
		{WorkloadF, 0.50, 0.00, 0.00, 0.00, 0.50},
	}

	for _, tc := range testCases {
		counts := make(map[Operation]int)
		for i := 0; i < iterations; i++ {
			op := ChooseOperation(tc.workload)
			counts[op]++
		}

		assertRatio(t, string(tc.workload), "READ", float64(counts[OperationRead])/float64(iterations), tc.expectedRead)
		assertRatio(t, string(tc.workload), "UPDATE", float64(counts[OperationUpdate])/float64(iterations), tc.expectedUpdate)
		assertRatio(t, string(tc.workload), "INSERT", float64(counts[OperationInsert])/float64(iterations), tc.expectedInsert)
		assertRatio(t, string(tc.workload), "SCAN", float64(counts[OperationScan])/float64(iterations), tc.expectedScan)
		assertRatio(t, string(tc.workload), "RMW", float64(counts[OperationReadModifyWrite])/float64(iterations), tc.expectedRMW)
	}
}

func TestParseWorkload(t *testing.T) {
	valid := []string{"A", "b", " C ", "D", "e", "F"}
	expected := []Workload{WorkloadA, WorkloadB, WorkloadC, WorkloadD, WorkloadE, WorkloadF}
	for i, v := range valid {
		w, err := ParseWorkload(v)
		if err != nil {
			t.Fatalf("ParseWorkload(%q) failed: %v", v, err)
		}
		if w != expected[i] {
			t.Fatalf("ParseWorkload(%q) = %v, expected %v", v, w, expected[i])
		}
	}

	invalid := []string{"G", "X", "", "invalid"}
	for _, inv := range invalid {
		if _, err := ParseWorkload(inv); err == nil {
			t.Errorf("Expected error for invalid workload %q, got nil", inv)
		}
	}
}

func TestParseDistribution(t *testing.T) {
	valid := map[string]KeyDistribution{
		"scrambled-zipfian": DistributionScrambledZipfian,
		"SCRAMBLEDZIPFIAN":  DistributionScrambledZipfian,
		"scrambled_zipfian": DistributionScrambledZipfian,
		"zipfian":           DistributionZipfian,
		"ZIPFIAN":           DistributionZipfian,
		"uniform":           DistributionUniform,
		"UNIFORM":           DistributionUniform,
	}
	for s, expected := range valid {
		d, err := ParseDistribution(s)
		if err != nil {
			t.Fatalf("ParseDistribution(%q) failed: %v", s, err)
		}
		if d != expected {
			t.Fatalf("ParseDistribution(%q) = %v, expected %v", s, d, expected)
		}
	}

	invalid := []string{"latest", "gaussian", "", "unknown"}
	for _, inv := range invalid {
		if _, err := ParseDistribution(inv); err == nil {
			t.Errorf("Expected error for invalid distribution %q, got nil", inv)
		}
	}
}

func TestStateSummaryAndAccessors(t *testing.T) {
	state := NewYcsbBenchmarkState(WorkloadB, DistributionScrambledZipfian, 1000, 12, 10, 100, false, true, "usertable")
	if state.Workload() != WorkloadB {
		t.Errorf("Expected WorkloadB, got %v", state.Workload())
	}
	if state.TableName() != "usertable" {
		t.Errorf("Expected usertable, got %v", state.TableName())
	}

	// Verify PrintSummary does not panic
	state.PrintSummary()
	state.readTotalDurationNs.Add(1000000)
	state.readOperationCount.Add(1)
	state.updateTotalDurationNs.Add(2000000)
	state.updateOperationCount.Add(1)
	state.insertTotalDurationNs.Add(3000000)
	state.insertOperationCount.Add(1)
	state.scanTotalDurationNs.Add(4000000)
	state.scanOperationCount.Add(1)
	state.rmwTotalDurationNs.Add(5000000)
	state.rmwOperationCount.Add(1)
	state.PrintSummary()
}

func assertRatio(t *testing.T, workload, opName string, actual, expected float64) {
	if expected == 0.0 && actual > 0.0 {
		t.Fatalf("[%s] Unexpected %s operations (got ratio %v, expected 0)", workload, opName, actual)
	}
	if expected > 0.0 && math.Abs(actual-expected) > 0.02 {
		t.Fatalf("[%s] Ratio for %s diverged: got %v, expected %v", workload, opName, actual, expected)
	}
}
