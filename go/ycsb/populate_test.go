package ycsb

import (
	"testing"
)

func TestComputePartitionRanges(t *testing.T) {
	ranges := computePartitionRanges(100, 4)
	if len(ranges) != 4 {
		t.Fatalf("Expected 4 ranges, got %d", len(ranges))
	}
	expected := []partitionRange{
		{0, 25},
		{25, 50},
		{50, 75},
		{75, 100},
	}
	for i, r := range ranges {
		if r != expected[i] {
			t.Errorf("Range %d mismatch: got %+v, expected %+v", i, r, expected[i])
		}
	}

	// Uneven partitions
	rangesUneven := computePartitionRanges(10, 3)
	if len(rangesUneven) != 3 {
		t.Fatalf("Expected 3 ranges, got %d", len(rangesUneven))
	}
	expectedUneven := []partitionRange{
		{0, 4},
		{4, 7},
		{7, 10},
	}
	for i, r := range rangesUneven {
		if r != expectedUneven[i] {
			t.Errorf("Uneven range %d mismatch: got %+v, expected %+v", i, r, expectedUneven[i])
		}
	}

	// Zero/negative inputs
	if r := computePartitionRanges(0, 4); r != nil {
		t.Errorf("Expected nil for 0 records, got %+v", r)
	}
	if r := computePartitionRanges(100, 0); r != nil {
		t.Errorf("Expected nil for 0 threads, got %+v", r)
	}

	// More threads than records
	rangesSmall := computePartitionRanges(3, 10)
	if len(rangesSmall) != 3 {
		t.Fatalf("Expected 3 ranges when threads > records, got %d", len(rangesSmall))
	}
	var totalRecords int64
	for _, r := range rangesSmall {
		totalRecords += r.end - r.start
	}
	if totalRecords != 3 {
		t.Fatalf("Expected total 3 records covered, got %d", totalRecords)
	}
}
