package ycsb

import (
	"math"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestZipfianBoundaryConditions(t *testing.T) {
	// Boundary test with items = 1
	gen1 := NewZipfianGenerator(1)
	for i := 0; i < 100; i++ {
		if val := gen1.NextInt64(); val != 0 {
			t.Fatalf("Expected 0 for items=1, got %d", val)
		}
	}

	// Boundary test with items = 2
	gen2 := NewZipfianGenerator(2)
	for i := 0; i < 100; i++ {
		val := gen2.NextInt64()
		if val < 0 || val > 1 {
			t.Fatalf("Expected 0 or 1 for items=2, got %d", val)
		}
	}

	generator := NewZipfianGeneratorRange(0, 1000)
	for i := 0; i < 10000; i++ {
		val := generator.NextInt64()
		if val < 0 || val > 1000 {
			t.Fatalf("Value out of bounds [0, 1000]: %d", val)
		}
	}
}

func TestZipfianUpperBoundsClamping(t *testing.T) {
	generator := NewZipfianGeneratorRange(10, 20)
	for i := 0; i < 5000; i++ {
		val := generator.NextInt64()
		if val < 10 || val > 20 {
			t.Fatalf("Value out of bounds [10, 20]: %d", val)
		}
	}
}

func TestComputeZetaSpeedAndLargeN(t *testing.T) {
	start := time.Now()
	z1 := ComputeZeta(100000000, DefaultZipfianConstant)
	duration := time.Since(start)
	if duration > 50*time.Millisecond {
		t.Fatalf("ComputeZeta took too long (%v), expected O(1)", duration)
	}

	z2 := ComputeZeta(10000000000, DefaultZipfianConstant)
	if math.Abs(z2-zetan10b) > 1e-3 {
		t.Fatalf("zetan10b mismatch: got %v, expected %v", z2, zetan10b)
	}
	if z1 <= 0.0 || z2 <= z1 {
		t.Fatalf("Zeta monotonicity violation: z1=%v, z2=%v", z1, z2)
	}
}

func TestComputeZetaContinuity(t *testing.T) {
	exact999 := zeta(999, DefaultZipfianConstant)
	approx999 := ComputeZeta(999, DefaultZipfianConstant)
	if math.Abs(exact999-approx999) > 1e-9 {
		t.Fatalf("Zeta discontinuity below 1000: exact=%v, approx=%v", exact999, approx999)
	}

	exact1000 := zeta(1000, DefaultZipfianConstant)
	approx1000 := ComputeZeta(1000, DefaultZipfianConstant)
	if math.Abs(exact1000-approx1000) > 1e-5 {
		t.Fatalf("Zeta discontinuity at 1000: exact=%v, approx=%v", exact1000, approx1000)
	}
}

func TestScrambledZipfianDistribution(t *testing.T) {
	generator := NewScrambledZipfianGenerator(0, 99999)
	for i := 0; i < 10000; i++ {
		val := generator.NextInt64()
		if val < 0 || val >= 100000 {
			t.Fatalf("Scrambled zipfian out of bounds [0, 99999]: %d", val)
		}
	}
}

func TestSkewedLatestGenerator(t *testing.T) {
	basis := &atomic.Int64{}
	basis.Store(100)
	generator := NewSkewedLatestGenerator(basis)

	for i := 0; i < 5000; i++ {
		val := generator.NextInt64()
		if val < 0 || val >= 100 {
			t.Fatalf("Skewed latest out of bounds [0, 99]: %d", val)
		}
	}

	// Test concurrent dynamic basis scaling
	var wg sync.WaitGroup
	for thread := 0; thread < 8; thread++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < 1000; i++ {
				basis.Add(1)
				val := generator.NextInt64()
				currBasis := basis.Load()
				if val < 0 || val >= currBasis {
					t.Errorf("Skewed latest concurrent value %d out of bounds [0, %d)", val, currBasis)
				}
			}
		}()
	}
	wg.Wait()
}

func TestBuildKeyName(t *testing.T) {
	if got := BuildKeyName(42, 0); got != "user42" {
		t.Fatalf("BuildKeyName(42, 0) = %s, expected user42", got)
	}
	if got := BuildKeyName(42, 5); got != "user00042" {
		t.Fatalf("BuildKeyName(42, 5) = %s, expected user00042", got)
	}
	if got := BuildKeyName(123456, 5); got != "user123456" {
		t.Fatalf("BuildKeyName(123456, 5) = %s, expected user123456", got)
	}

	state := NewYcsbBenchmarkState(WorkloadB, DistributionScrambledZipfian, 1000, 0, 10, 100, false, false, "usertable")
	if state.zeroPadding != 0 {
		t.Fatalf("Expected zeroPadding to remain 0, got %d", state.zeroPadding)
	}
}

func TestGenerateRandomString(t *testing.T) {
	s1 := GenerateRandomString(100)
	if len(s1) != 100 {
		t.Fatalf("Expected string of len 100, got %d", len(s1))
	}
	s2 := GenerateRandomString(100)
	if s1 == s2 {
		t.Fatalf("Expected two random strings to differ")
	}
}

func TestFnvHash64(t *testing.T) {
	h1 := FnvHash64(0)
	h2 := FnvHash64(1)
	h3 := FnvHash64(math.MinInt64)

	if h1 < 0 || h2 < 0 || h3 < 0 {
		t.Fatalf("FNV hash must be non-negative: h1=%d, h2=%d, h3=%d", h1, h2, h3)
	}
	if h1 == h2 {
		t.Fatalf("Expected distinct hashes for 0 and 1: %d vs %d", h1, h2)
	}
}
