package ycsb

import (
	"math"
	"math/rand/v2"
	"sync/atomic"
)

const (
	// DefaultZipfianConstant is the standard Zipfian skew parameter (0.99) defined in the YCSB specification.
	DefaultZipfianConstant = 0.99
	defaultItemCount       = int64(10000000000)
	zetan1k                = 7.728953217284738
	zetan10b               = 26.46902820178302
	fnvOffsetBasis64       = uint64(0xcbf29ce484222325)
	fnvPrime64             = uint64(0x100000001b3)
)

// ZipfianGenerator generates random integers following a Zipfian distribution over [base, base + items - 1].
type ZipfianGenerator struct {
	items           int64
	base            int64
	zipfianConstant float64
	alpha           float64
	zetan           float64
	eta             float64
	zeta2theta      float64
}

// NewZipfianGenerator creates a new ZipfianGenerator over [0, items - 1] with the default Zipfian constant.
func NewZipfianGenerator(items int64) *ZipfianGenerator {
	return NewZipfianGeneratorWithConstant(0, items-1, DefaultZipfianConstant)
}

// NewZipfianGeneratorRange creates a new ZipfianGenerator over [min, max] with the default Zipfian constant.
func NewZipfianGeneratorRange(min, max int64) *ZipfianGenerator {
	return NewZipfianGeneratorWithConstant(min, max, DefaultZipfianConstant)
}

// NewZipfianGeneratorWithConstant creates a new ZipfianGenerator over [min, max] with a custom Zipfian constant.
func NewZipfianGeneratorWithConstant(min, max int64, zipfianConstant float64) *ZipfianGenerator {
	items := max - min + 1
	zetan := ComputeZeta(items, zipfianConstant)
	return NewZipfianGeneratorWithZetan(min, max, zipfianConstant, zetan)
}

// NewZipfianGeneratorWithZetan creates a new ZipfianGenerator using precomputed zetan over [min, max].
func NewZipfianGeneratorWithZetan(min, max int64, zipfianConstant, zetan float64) *ZipfianGenerator {
	items := max - min + 1
	if items < 1 {
		items = 1
	}
	zeta2theta := 1.0 + math.Pow(0.5, zipfianConstant)
	alpha := 1.0 / (1.0 - zipfianConstant)
	eta := (1.0 - math.Pow(2.0/float64(items), 1.0-zipfianConstant)) / (1.0 - zeta2theta/zetan)

	return &ZipfianGenerator{
		items:           items,
		base:            min,
		zipfianConstant: zipfianConstant,
		alpha:           alpha,
		zetan:           zetan,
		eta:             eta,
		zeta2theta:      zeta2theta,
	}
}

// NextInt64 generates the next random integer in [base, base + items - 1].
func (g *ZipfianGenerator) NextInt64() int64 {
	if g.items <= 1 {
		return g.base
	}

	u := rand.Float64()
	uz := u * g.zetan

	if uz < 1.0 {
		return g.base
	}

	if uz < g.zeta2theta {
		return g.base + 1
	}

	result := g.base + int64(float64(g.items)*math.Pow(g.eta*u-g.eta+1.0, g.alpha))
	maxVal := g.base + g.items - 1
	if result > maxVal {
		return maxVal
	}
	return result
}

// NextKey generates the next random key string formatted according to the configured zero padding width.
func (g *ZipfianGenerator) NextKey(zeroPadding int) string {
	return BuildKeyName(g.NextInt64(), zeroPadding)
}

// ComputeZeta computes or approximates the generalized harmonic number zeta(n, theta) in O(1) time
// using precomputed constants and integral approximation (Jim Gray et al., SIGMOD '94).
func ComputeZeta(n int64, theta float64) float64 {
	if math.Abs(theta-DefaultZipfianConstant) < 1e-9 {
		if n >= 1000 {
			return zetan1k + (math.Pow(float64(n), 1.0-theta)-math.Pow(1000.0, 1.0-theta))/(1.0-theta)
		}
		return zeta(n, theta)
	}

	n0 := n
	if n0 > 1000 {
		n0 = 1000
	}
	sum := zeta(n0, theta)
	if n > n0 {
		sum += (math.Pow(float64(n), 1.0-theta) - math.Pow(float64(n0), 1.0-theta)) / (1.0 - theta)
	}
	return sum
}

// zeta computes the exact discrete sum sum_{i=1}^n (1 / i^theta).
func zeta(n int64, theta float64) float64 {
	sum := 0.0
	for i := int64(0); i < n; i++ {
		sum += 1.0 / math.Pow(float64(i+1), theta)
	}
	return sum
}

// ScrambledZipfianGenerator generates a scrambled Zipfian distribution scattering popular items across key space.
type ScrambledZipfianGenerator struct {
	generator *ZipfianGenerator
	min       int64
	itemCount int64
}

// NewScrambledZipfianGenerator creates a new ScrambledZipfianGenerator over [min, max] with the default Zipfian constant.
func NewScrambledZipfianGenerator(min, max int64) *ScrambledZipfianGenerator {
	return NewScrambledZipfianGeneratorWithConstant(min, max, DefaultZipfianConstant)
}

// NewScrambledZipfianGeneratorWithConstant creates a new ScrambledZipfianGenerator with a custom Zipfian constant.
func NewScrambledZipfianGeneratorWithConstant(min, max int64, zipfianConstant float64) *ScrambledZipfianGenerator {
	itemCount := max - min + 1
	var generator *ZipfianGenerator
	if math.Abs(zipfianConstant-DefaultZipfianConstant) < 1e-9 {
		generator = NewZipfianGeneratorWithZetan(0, defaultItemCount-1, DefaultZipfianConstant, zetan10b)
	} else {
		generator = NewZipfianGeneratorWithConstant(0, defaultItemCount-1, zipfianConstant)
	}

	return &ScrambledZipfianGenerator{
		generator: generator,
		min:       min,
		itemCount: itemCount,
	}
}

// NextInt64 generates the next scrambled random integer in [min, max].
func (g *ScrambledZipfianGenerator) NextInt64() int64 {
	rawZipfian := g.generator.NextInt64()
	hashed := FnvHash64(rawZipfian)
	return g.min + (hashed % g.itemCount)
}

// NextKey generates the next scrambled key formatted with the specified zero padding.
func (g *ScrambledZipfianGenerator) NextKey(zeroPadding int) string {
	return BuildKeyName(g.NextInt64(), zeroPadding)
}

// FnvHash64 computes the 64-bit FNV-1a hash matching standard YCSB key scrambling.
func FnvHash64(value int64) int64 {
	hash := fnvOffsetBasis64
	val := uint64(value)
	for i := 0; i < 8; i++ {
		octet := val & 0xff
		val >>= 8
		hash ^= octet
		hash *= fnvPrime64
	}
	signedHash := int64(hash)
	if signedHash < 0 {
		if signedHash == math.MinInt64 {
			return 0
		}
		return -signedHash
	}
	return signedHash
}

type zipfianParams struct {
	itemCount int64
	zetan     float64
	eta       float64
}

func newZipfianParams(itemCount int64, zipfianConstant, zeta2theta float64) *zipfianParams {
	zetan := ComputeZeta(itemCount, zipfianConstant)
	eta := (1.0 - math.Pow(2.0/float64(itemCount), 1.0-zipfianConstant)) / (1.0 - zeta2theta/zetan)
	return &zipfianParams{
		itemCount: itemCount,
		zetan:     zetan,
		eta:       eta,
	}
}

// SkewedLatestGenerator generates keys with a Zipfian skew towards the most recently inserted records.
type SkewedLatestGenerator struct {
	basis           *atomic.Int64
	zipfianConstant float64
	zeta2theta      float64
	alpha           float64
	cachedParams    atomic.Pointer[zipfianParams]
}

// NewSkewedLatestGenerator creates a new SkewedLatestGenerator tracking the supplied basis counter with default skew.
func NewSkewedLatestGenerator(basis *atomic.Int64) *SkewedLatestGenerator {
	return NewSkewedLatestGeneratorWithConstant(basis, DefaultZipfianConstant)
}

// NewSkewedLatestGeneratorWithConstant creates a new SkewedLatestGenerator with a custom Zipfian constant.
func NewSkewedLatestGeneratorWithConstant(basis *atomic.Int64, zipfianConstant float64) *SkewedLatestGenerator {
	zeta2theta := 1.0 + math.Pow(0.5, zipfianConstant)
	alpha := 1.0 / (1.0 - zipfianConstant)
	initial := basis.Load()
	if initial < 2 {
		initial = 2
	}
	g := &SkewedLatestGenerator{
		basis:           basis,
		zipfianConstant: zipfianConstant,
		zeta2theta:      zeta2theta,
		alpha:           alpha,
	}
	g.cachedParams.Store(newZipfianParams(initial, zipfianConstant, zeta2theta))
	return g
}

// NextInt64 generates the next skewed-latest random integer.
func (g *SkewedLatestGenerator) NextInt64() int64 {
	maxBasis := g.basis.Load()
	if maxBasis <= 1 {
		return 0
	}

	cached := g.cachedParams.Load()
	var zetan, eta float64
	if cached != nil && cached.itemCount == maxBasis {
		zetan = cached.zetan
		eta = cached.eta
	} else {
		newParams := newZipfianParams(maxBasis, g.zipfianConstant, g.zeta2theta)
		g.cachedParams.Store(newParams)
		zetan = newParams.zetan
		eta = newParams.eta
	}

	u := rand.Float64()
	uz := u * zetan

	if uz < 1.0 {
		return maxBasis - 1
	}
	if uz < g.zeta2theta {
		res := maxBasis - 2
		if res < 0 {
			return 0
		}
		return res
	}

	offset := int64(float64(maxBasis) * math.Pow(eta*u-eta+1.0, g.alpha))
	key := maxBasis - 1 - offset
	if key < 0 {
		return 0
	}
	return key
}

// NextKey generates the next skewed latest key formatted with zero padding.
func (g *SkewedLatestGenerator) NextKey(zeroPadding int) string {
	return BuildKeyName(g.NextInt64(), zeroPadding)
}
