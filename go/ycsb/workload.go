package ycsb

import (
	"fmt"
	"math/rand/v2"
	"strings"
)

// Workload represents one of the standard YCSB core workloads (A through F).
type Workload string

const (
	// WorkloadA represents Workload A (50% Read, 50% Update).
	WorkloadA Workload = "A"
	// WorkloadB represents Workload B (95% Read, 5% Update).
	WorkloadB Workload = "B"
	// WorkloadC represents Workload C (100% Read).
	WorkloadC Workload = "C"
	// WorkloadD represents Workload D (95% Read Latest, 5% Insert).
	WorkloadD Workload = "D"
	// WorkloadE represents Workload E (95% Short Range Scan, 5% Insert).
	WorkloadE Workload = "E"
	// WorkloadF represents Workload F (50% Read, 50% Read-Modify-Write).
	WorkloadF Workload = "F"
)

// ParseWorkload parses a workload identifier string into a Workload type.
func ParseWorkload(s string) (Workload, error) {
	switch strings.ToUpper(strings.TrimSpace(s)) {
	case "A":
		return WorkloadA, nil
	case "B":
		return WorkloadB, nil
	case "C":
		return WorkloadC, nil
	case "D":
		return WorkloadD, nil
	case "E":
		return WorkloadE, nil
	case "F":
		return WorkloadF, nil
	default:
		return "", fmt.Errorf("unknown YCSB workload %q (expected A, B, C, D, E, or F)", s)
	}
}

// KeyDistribution represents the key selection distribution.
type KeyDistribution string

const (
	// DistributionScrambledZipfian represents the Scrambled Zipfian distribution (default).
	DistributionScrambledZipfian KeyDistribution = "scrambled-zipfian"
	// DistributionZipfian represents the standard Zipfian distribution.
	DistributionZipfian KeyDistribution = "zipfian"
	// DistributionUniform represents the uniform random distribution.
	DistributionUniform KeyDistribution = "uniform"
)

// ParseDistribution parses a distribution string into a KeyDistribution type.
func ParseDistribution(s string) (KeyDistribution, error) {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "scrambled-zipfian", "scrambledzipfian", "scrambled_zipfian":
		return DistributionScrambledZipfian, nil
	case "zipfian":
		return DistributionZipfian, nil
	case "uniform":
		return DistributionUniform, nil
	default:
		return "", fmt.Errorf("unknown key distribution %q (expected scrambled-zipfian, zipfian, or uniform)", s)
	}
}

// Operation represents an individual YCSB transaction operation type.
type Operation int

const (
	// OperationRead performs a point lookup for a single record.
	OperationRead Operation = iota
	// OperationUpdate performs a blind write updating a random field in a record.
	OperationUpdate
	// OperationInsert inserts a brand new record with all fields populated.
	OperationInsert
	// OperationScan performs a short range scan starting at a key.
	OperationScan
	// OperationReadModifyWrite reads a record and writes back an updated field atomically.
	OperationReadModifyWrite
)

// String returns the string representation of the operation.
func (op Operation) String() string {
	switch op {
	case OperationRead:
		return "READ"
	case OperationUpdate:
		return "UPDATE"
	case OperationInsert:
		return "INSERT"
	case OperationScan:
		return "SCAN"
	case OperationReadModifyWrite:
		return "RMW"
	default:
		return "UNKNOWN"
	}
}

// ChooseOperation selects an operation according to the specified YCSB workload distribution.
func ChooseOperation(workload Workload) Operation {
	switch workload {
	case WorkloadA:
		// 50% Read, 50% Update
		if rand.Float64() < 0.5 {
			return OperationRead
		}
		return OperationUpdate

	case WorkloadB:
		// 95% Read, 5% Update
		if rand.Float64() < 0.95 {
			return OperationRead
		}
		return OperationUpdate

	case WorkloadC:
		// 100% Read
		return OperationRead

	case WorkloadD:
		// 95% Read (latest), 5% Insert
		if rand.Float64() < 0.95 {
			return OperationRead
		}
		return OperationInsert

	case WorkloadE:
		// 95% Scan, 5% Insert
		if rand.Float64() < 0.95 {
			return OperationScan
		}
		return OperationInsert

	case WorkloadF:
		// 50% Read, 50% Read-Modify-Write
		if rand.Float64() < 0.5 {
			return OperationRead
		}
		return OperationReadModifyWrite

	default:
		return OperationRead
	}
}
