// Copyright 2026 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

use arc_swap::ArcSwap;
use rand::{RngExt, random_range, rng};
use std::cmp::{max, min};
use std::sync::Arc;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicI64, Ordering};

pub(crate) const DEFAULT_ZIPFIAN_CONSTANT: f64 = 0.99;
pub(crate) const ZETAN_1K: f64 = 7.728953217284738;
pub(crate) const ZETAN_10B: f64 = 26.46902820178302;
pub(crate) const DEFAULT_ITEM_COUNT: i64 = 10_000_000_000;

/// A generator of a Zipfian distribution, based on the algorithm by Jim Gray et al.
/// ("Quickly Generating Billion-Record Synthetic Databases", SIGMOD 1994).
#[derive(Clone, Debug)]
pub struct ZipfianGenerator {
    items: i64,
    base: i64,
    zipfian_constant: f64,
    alpha: f64,
    zetan: f64,
    eta: f64,
    zeta_2_theta: f64,
}

impl ZipfianGenerator {
    /// Creates a new `ZipfianGenerator` over the interval `[min, max]` with the default Zipfian constant (`0.99`).
    pub fn new(min: i64, max: i64) -> Self {
        Self::new_with_constant(min, max, DEFAULT_ZIPFIAN_CONSTANT)
    }

    /// Creates a new `ZipfianGenerator` over the interval `[min, max]` with a custom Zipfian constant.
    pub fn new_with_constant(min: i64, max: i64, zipfian_constant: f64) -> Self {
        let items = max - min + 1;
        let zetan = Self::compute_zeta(items, zipfian_constant);
        Self::new_with_zetan(min, max, zipfian_constant, zetan)
    }

    /// Creates a new `ZipfianGenerator` over `[min, max]` with a precomputed $\zeta_n$ value to avoid slow initialization.
    pub(crate) fn new_with_zetan(min: i64, max: i64, zipfian_constant: f64, zetan: f64) -> Self {
        let items = max - min + 1;
        let alpha = 1.0 / (1.0 - zipfian_constant);
        let zeta_2_theta = Self::zeta(2, zipfian_constant);
        let eta = (1.0 - (2.0 / items as f64).powf(1.0 - zipfian_constant))
            / (1.0 - zeta_2_theta / zetan);

        Self {
            items,
            base: min,
            zipfian_constant,
            alpha,
            zetan,
            eta,
            zeta_2_theta,
        }
    }

    /// Generates the next random value according to the Zipfian distribution.
    pub fn next_i64(&self) -> i64 {
        let u: f64 = random_range(0.0..1.0);
        let uz = u * self.zetan;

        if uz < 1.0 {
            return self.base;
        }

        if uz < self.zeta_2_theta {
            return self.base + 1;
        }

        let result = self.base
            + (self.items as f64 * (self.eta * u - self.eta + 1.0).powf(self.alpha)) as i64;
        min(self.base + self.items - 1, result)
    }

    /// Generates the next random value dynamically re-scaled to a custom item count in $O(1)$ time.
    pub fn next_i64_with_item_count(&self, item_count: i64) -> i64 {
        if item_count <= 1 {
            return 0;
        }
        let u: f64 = random_range(0.0..1.0);
        let zetan = Self::compute_zeta(item_count, self.zipfian_constant);
        let uz = u * zetan;

        if uz < 1.0 {
            return 0;
        }

        if uz < self.zeta_2_theta {
            return 1;
        }

        let local_eta = (1.0 - (2.0 / item_count as f64).powf(1.0 - self.zipfian_constant))
            / (1.0 - self.zeta_2_theta / zetan);
        let result =
            (item_count as f64 * (local_eta * u - local_eta + 1.0).powf(self.alpha)) as i64;
        min(item_count - 1, result)
    }

    /// Formats an integer key into the standard YCSB zero-padded primary key string (e.g. `user000000000001`).
    pub fn build_key_name(key_number: i64, zero_padding: usize) -> String {
        if zero_padding == 0 {
            format!("user{}", key_number)
        } else {
            let key_str = key_number.to_string();
            if key_str.len() >= zero_padding {
                format!("user{}", key_str)
            } else {
                let padding_zeros = zero_padding - key_str.len();
                let mut key = String::with_capacity(4 + zero_padding);
                key.push_str("user");
                for _ in 0..padding_zeros {
                    key.push('0');
                }
                key.push_str(&key_str);
                key
            }
        }
    }

    /// Generates the next random key string formatted according to the configured zero padding width.
    pub fn next_key(&self, zero_padding: usize) -> String {
        Self::build_key_name(self.next_i64(), zero_padding)
    }

    /// Computes or approximates the generalized harmonic number $\zeta(n, \theta)$ in $O(1)$ time
    /// using precomputed constants and integral approximation (Jim Gray et al., SIGMOD '94).
    pub fn compute_zeta(n: i64, theta: f64) -> f64 {
        if (theta - DEFAULT_ZIPFIAN_CONSTANT).abs() < f64::EPSILON {
            if n >= 1_000 {
                return ZETAN_1K
                    + ((n as f64).powf(1.0 - theta) - (1_000.0_f64).powf(1.0 - theta))
                        / (1.0 - theta);
            }
            return Self::zeta(n, theta);
        }

        let n0 = min(n, 1000);
        let mut sum = Self::zeta(n0, theta);
        if n > n0 {
            sum += ((n as f64).powf(1.0 - theta) - (n0 as f64).powf(1.0 - theta)) / (1.0 - theta);
        }
        sum
    }

    /// Computes the exact discrete sum $\sum_{i=1}^n \frac{1}{i^\theta}$.
    pub fn zeta(n: i64, theta: f64) -> f64 {
        let mut sum = 0.0;
        for i in 0..n {
            sum += 1.0 / ((i + 1) as f64).powf(theta);
        }
        sum
    }
}

#[cfg(test)]
impl ZipfianGenerator {
    /// Returns the total item count in the distribution.
    pub(crate) fn items(&self) -> i64 {
        self.items
    }

    /// Returns the base (minimum) value of the key space.
    pub(crate) fn base(&self) -> i64 {
        self.base
    }

    /// Returns the configured Zipfian constant $\theta$.
    pub(crate) fn zipfian_constant(&self) -> f64 {
        self.zipfian_constant
    }

    /// Returns the precomputed $\zeta(2, \theta)$ value.
    pub(crate) fn zeta_2_theta(&self) -> f64 {
        self.zeta_2_theta
    }
}

/// A generator of a scrambled Zipfian distribution that scatters the popular items across the entire
/// key space using FNV-1a 64-bit hashing.
#[derive(Clone, Debug)]
pub struct ScrambledZipfianGenerator {
    generator: ZipfianGenerator,
    min: i64,
    item_count: i64,
}

impl ScrambledZipfianGenerator {
    /// Creates a new `ScrambledZipfianGenerator` over the range `[min, max]` with the default Zipfian constant.
    pub fn new(min: i64, max: i64) -> Self {
        Self::new_with_constant(min, max, DEFAULT_ZIPFIAN_CONSTANT)
    }

    /// Creates a new `ScrambledZipfianGenerator` with a custom Zipfian constant.
    pub fn new_with_constant(min: i64, max: i64, zipfian_constant: f64) -> Self {
        let item_count = max - min + 1;
        let generator = if (zipfian_constant - DEFAULT_ZIPFIAN_CONSTANT).abs() < f64::EPSILON {
            ZipfianGenerator::new_with_zetan(
                0,
                DEFAULT_ITEM_COUNT,
                DEFAULT_ZIPFIAN_CONSTANT,
                ZETAN_10B,
            )
        } else {
            ZipfianGenerator::new_with_constant(0, DEFAULT_ITEM_COUNT, zipfian_constant)
        };

        Self {
            generator,
            min,
            item_count,
        }
    }

    /// Generates the next scrambled random integer in `[min, max]`.
    pub fn next_i64(&self) -> i64 {
        let raw_zipfian = self.generator.next_i64();
        let hashed = Self::fnv_hash_64(raw_zipfian);
        self.min + (hashed % self.item_count)
    }

    /// Generates the next scrambled key formatted with the specified zero padding.
    pub fn next_key(&self, zero_padding: usize) -> String {
        ZipfianGenerator::build_key_name(self.next_i64(), zero_padding)
    }

    /// Computes the 64-bit FNV-1a hash matching standard YCSB key scrambling.
    pub fn fnv_hash_64(mut value: i64) -> i64 {
        const FNV_OFFSET_BASIS_64: u64 = 0xcbf29ce484222325;
        const FNV_PRIME_64: u64 = 0x100000001b3;

        let mut hash: u64 = FNV_OFFSET_BASIS_64;
        for _ in 0..8 {
            let octet = (value as u64) & 0xff;
            value = (value as u64 >> 8) as i64;
            hash ^= octet;
            hash = hash.wrapping_mul(FNV_PRIME_64);
        }
        let signed_hash = hash as i64;
        if signed_hash < 0 {
            if signed_hash == i64::MIN {
                0
            } else {
                -signed_hash
            }
        } else {
            signed_hash
        }
    }
}

#[derive(Clone, Copy, Debug)]
struct ZipfianParams {
    item_count: i64,
    zetan: f64,
    eta: f64,
}

impl ZipfianParams {
    fn new(item_count: i64, zipfian_constant: f64, zeta_2_theta: f64) -> Self {
        let zetan = ZipfianGenerator::compute_zeta(item_count, zipfian_constant);
        let eta = (1.0 - (2.0 / item_count as f64).powf(1.0 - zipfian_constant))
            / (1.0 - zeta_2_theta / zetan);
        Self {
            item_count,
            zetan,
            eta,
        }
    }
}

/// Generates keys with a Zipfian skew towards the most recently inserted records. Used in YCSB
/// Workload D ("Read Latest").
#[derive(Debug)]
pub struct SkewedLatestGenerator {
    basis: Arc<AtomicI64>,
    zipfian_constant: f64,
    zeta_2_theta: f64,
    alpha: f64,
    cached_params: ArcSwap<ZipfianParams>,
}

impl SkewedLatestGenerator {
    /// Creates a new `SkewedLatestGenerator` tracking the supplied `basis` counter with default Zipfian skew.
    pub fn new(basis: Arc<AtomicI64>) -> Self {
        Self::new_with_constant(basis, DEFAULT_ZIPFIAN_CONSTANT)
    }

    /// Creates a new `SkewedLatestGenerator` with a custom Zipfian constant.
    pub fn new_with_constant(basis: Arc<AtomicI64>, zipfian_constant: f64) -> Self {
        let zeta_2_theta = 1.0 + (0.5_f64).powf(zipfian_constant);
        let alpha = 1.0 / (1.0 - zipfian_constant);
        let initial = basis.load(Ordering::Relaxed);
        let cached_params = ArcSwap::from_pointee(ZipfianParams::new(
            max(2, initial),
            zipfian_constant,
            zeta_2_theta,
        ));

        Self {
            basis,
            zipfian_constant,
            zeta_2_theta,
            alpha,
            cached_params,
        }
    }

    pub fn next_i64(&self) -> i64 {
        let max_basis = self.basis.load(Ordering::Relaxed);
        if max_basis <= 1 {
            return 0;
        }

        let cached = self.cached_params.load();
        let (zetan, eta) = if cached.item_count == max_basis {
            (cached.zetan, cached.eta)
        } else {
            let new_params = Arc::new(ZipfianParams::new(
                max_basis,
                self.zipfian_constant,
                self.zeta_2_theta,
            ));
            self.cached_params.store(Arc::clone(&new_params));
            (new_params.zetan, new_params.eta)
        };

        let u: f64 = random_range(0.0..1.0);
        let uz = u * zetan;

        if uz < 1.0 {
            return max_basis - 1;
        }
        if uz < self.zeta_2_theta {
            return max(0, max_basis - 2);
        }

        let offset = (max_basis as f64 * (eta * u - eta + 1.0).powf(self.alpha)) as i64;
        let key = max_basis - 1 - offset;
        max(0, key)
    }

    pub fn next_key(&self, zero_padding: usize) -> String {
        ZipfianGenerator::build_key_name(self.next_i64(), zero_padding)
    }
}

const ASCII_POOL_SIZE: usize = 16384;
const ASCII_CHARS: &str = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

static ASCII_POOL: OnceLock<String> = OnceLock::new();

fn get_ascii_pool() -> &'static str {
    ASCII_POOL.get_or_init(|| {
        let chars = ASCII_CHARS.as_bytes();
        let mut pool = String::with_capacity(ASCII_POOL_SIZE);
        for index in 0..ASCII_POOL_SIZE {
            pool.push(chars[index % chars.len()] as char);
        }
        pool
    })
}

/// Generates a pseudo-random ASCII alphanumeric string of the requested length.
pub fn generate_random_string(length: usize) -> String {
    if length == 0 {
        return String::new();
    }
    let pool = get_ascii_pool();
    let mut random_number_generator = rng();

    if length <= ASCII_POOL_SIZE {
        let maximum_offset = ASCII_POOL_SIZE - length;
        let offset = if maximum_offset > 0 {
            random_number_generator.random_range(0..maximum_offset)
        } else {
            0
        };
        pool[offset..offset + length].to_string()
    } else {
        let mut buffer = String::with_capacity(length);
        let mut offset = 0;
        while offset < length {
            let chunk_size = min(ASCII_POOL_SIZE, length - offset);
            let pool_offset =
                random_number_generator.random_range(0..=(ASCII_POOL_SIZE - chunk_size));
            buffer.push_str(&pool[pool_offset..pool_offset + chunk_size]);
            offset += chunk_size;
        }
        buffer
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;
    use std::fmt::Debug;
    use std::time::Instant;

    #[test]
    fn zipfian_bounds_and_keys() -> anyhow::Result<()> {
        let generator = ZipfianGenerator::new(0, 999);
        assert_eq!(generator.items(), 1000, "Items count must be 1000");
        assert_eq!(generator.base(), 0, "Base must be 0");

        for _ in 0..10_000 {
            let value = generator.next_i64();
            assert!(
                (0..1000).contains(&value),
                "Generated value {} must be within [0, 1000)",
                value
            );
        }

        let key_zero = ZipfianGenerator::build_key_name(0, 12);
        assert_eq!(
            key_zero, "user000000000000",
            "Key 0 must be zero-padded to 12 zeros"
        );

        let key_one = ZipfianGenerator::build_key_name(1, 12);
        assert_eq!(
            key_one, "user000000000001",
            "Key 1 must be zero-padded to 12 digits"
        );

        let key = ZipfianGenerator::build_key_name(42, 12);
        assert_eq!(
            key, "user000000000042",
            "Key name must be correctly zero-padded to 12 digits"
        );

        assert!(
            (generator.zipfian_constant() - DEFAULT_ZIPFIAN_CONSTANT).abs() < f64::EPSILON,
            "Zipfian constant should match DEFAULT_ZIPFIAN_CONSTANT"
        );
        assert!(
            (generator.zeta_2_theta() - (1.0 + (0.5_f64).powf(DEFAULT_ZIPFIAN_CONSTANT))).abs()
                < 1e-9,
            "zeta_2_theta should match 1.0 + 0.5^theta"
        );
        const {
            assert!(
                ZETAN_1K > 0.0 && ZETAN_10B > ZETAN_1K,
                "Precomputed ZETAN constants should be positive and monotonically increasing"
            );
        }

        let key_no_pad = ZipfianGenerator::build_key_name(12345, 4);
        assert_eq!(
            key_no_pad, "user12345",
            "Key with length > padding should not truncate"
        );

        let key_overflow = ZipfianGenerator::build_key_name(1234567890123, 12);
        assert_eq!(
            key_overflow, "user1234567890123",
            "Key exceeding padding width should not truncate"
        );

        Ok(())
    }

    #[test]
    fn zipfian_generator_distribution_skew() -> anyhow::Result<()> {
        let record_count = 1000;
        let generator = ZipfianGenerator::new(0, record_count - 1);
        let mut counts = vec![0usize; record_count as usize];
        let samples = 50_000;

        for _ in 0..samples {
            let value = generator.next_i64();
            assert!(
                value >= 0 && value < record_count,
                "Generated value {} must be in range [0, {})",
                value,
                record_count
            );
            counts[value as usize] += 1;
        }

        let top_10_percent = (record_count as f64 * 0.1) as usize;
        let top_hits: usize = counts[0..top_10_percent].iter().sum();
        let bottom_hits: usize = counts[(record_count as usize - top_10_percent)..]
            .iter()
            .sum();

        assert!(
            top_hits > bottom_hits * 2,
            "Top 10% keys hits ({}) should be > 2x bottom 10% hits ({})",
            top_hits,
            bottom_hits
        );
        assert!(
            counts[0] > counts[record_count as usize - 1],
            "Key 0 count ({}) should exceed last key count ({})",
            counts[0],
            counts[record_count as usize - 1]
        );

        Ok(())
    }

    #[test]
    fn zipfian_boundary_conditions() -> anyhow::Result<()> {
        let generator1 = ZipfianGenerator::new(0, 0);
        assert_eq!(
            generator1.next_i64(),
            0,
            "Single-element Zipfian generator must return 0"
        );

        let generator2 = ZipfianGenerator::new(0, 1);
        for _ in 0..100 {
            let value = generator2.next_i64();
            assert!(
                value == 0 || value == 1,
                "Two-element Zipfian generator must return 0 or 1, got {}",
                value
            );
        }

        Ok(())
    }

    #[test]
    fn compute_zeta_continuity() -> anyhow::Result<()> {
        let theta = DEFAULT_ZIPFIAN_CONSTANT;
        let zeta999 = ZipfianGenerator::compute_zeta(999, theta);
        let zeta1000 = ZipfianGenerator::compute_zeta(1000, theta);
        let zeta1001 = ZipfianGenerator::compute_zeta(1001, theta);

        assert!(
            (zeta1000 - ZETAN_1K).abs() < 1e-9,
            "Zeta for 1000 items should match precomputed constant ZETAN_1K within epsilon"
        );
        assert!(zeta1000 > zeta999, "zeta(1000) must be > zeta(999)");
        assert!(
            zeta1000 - zeta999 < 0.01,
            "Difference around 1000 boundary must be smooth"
        );
        assert!(zeta1001 > zeta1000, "zeta(1001) must be > zeta(1000)");
        assert!(
            zeta1001 - zeta1000 < 0.01,
            "Difference around 1000 boundary must be smooth"
        );

        let zeta99999 = ZipfianGenerator::compute_zeta(99999, theta);
        let zeta100000 = ZipfianGenerator::compute_zeta(100000, theta);
        let zeta100001 = ZipfianGenerator::compute_zeta(100001, theta);

        assert!(zeta100000 > zeta99999, "zeta(100000) must be > zeta(99999)");
        assert!(
            zeta100000 - zeta99999 < 0.001,
            "Difference around 100000 boundary must be smooth"
        );
        assert!(
            zeta100001 > zeta100000,
            "zeta(100001) must be > zeta(100000)"
        );
        assert!(
            zeta100001 - zeta100000 < 0.001,
            "Difference around 100000 boundary must be smooth"
        );

        Ok(())
    }

    #[test]
    fn scrambled_zipfian_distribution() -> anyhow::Result<()> {
        let generator = ScrambledZipfianGenerator::new(0, 99_999);
        let mut seen = HashSet::new();

        for _ in 0..10_000 {
            let value = generator.next_i64();
            assert!(
                (0..100_000).contains(&value),
                "Scrambled value {} must be in [0, 100000)",
                value
            );
            seen.insert(value);
        }

        assert!(
            seen.len() > 1000,
            "Scrambled distribution should scatter across many distinct keys"
        );

        let hash1 = ScrambledZipfianGenerator::fnv_hash_64(12345);
        let hash2 = ScrambledZipfianGenerator::fnv_hash_64(12345);
        let hash3 = ScrambledZipfianGenerator::fnv_hash_64(54321);
        assert_eq!(hash1, hash2, "Hash must be deterministic");
        assert_ne!(
            hash1, hash3,
            "Different inputs must produce different hashes"
        );

        assert!(
            ScrambledZipfianGenerator::fnv_hash_64(0) >= 0,
            "Hash of 0 must be non-negative"
        );
        assert!(
            ScrambledZipfianGenerator::fnv_hash_64(-1) >= 0,
            "Hash of -1 must be non-negative"
        );
        assert!(
            ScrambledZipfianGenerator::fnv_hash_64(i64::MIN) >= 0,
            "Hash of i64::MIN must be non-negative"
        );
        assert!(
            ScrambledZipfianGenerator::fnv_hash_64(i64::MAX) >= 0,
            "Hash of i64::MAX must be non-negative"
        );

        Ok(())
    }

    #[test]
    fn skewed_latest_generator() -> anyhow::Result<()> {
        let basis = Arc::new(AtomicI64::new(1000));
        let generator = SkewedLatestGenerator::new(Arc::clone(&basis));
        let mut recent_hits = 0;
        let samples = 5000;

        for _ in 0..samples {
            let value = generator.next_i64();
            assert!(
                (0..1000).contains(&value),
                "Skewed latest value {} must be in [0, 1000)",
                value
            );
            if value >= 800 {
                recent_hits += 1;
            }
        }

        assert!(
            recent_hits > samples / 2,
            "Skewed latest should favor recent items (> 50% in top 20%, got {})",
            recent_hits
        );

        // Increment basis and verify it adapts dynamically
        basis.store(2000, Ordering::Relaxed);
        let value = generator.next_i64();
        assert!(
            value < 2000,
            "Skewed latest value {} must be under new basis 2000",
            value
        );

        Ok(())
    }

    #[test]
    fn random_string_generation() -> anyhow::Result<()> {
        let empty = generate_random_string(0);
        assert_eq!(empty, "", "Zero length must produce empty string");

        let short_string = generate_random_string(100);
        assert_eq!(short_string.len(), 100, "Short string must have length 100");
        assert!(
            short_string.chars().all(|c| c.is_ascii_alphanumeric()),
            "Short string must contain valid ASCII alphanumeric chars"
        );

        let boundary_string = generate_random_string(16384);
        assert_eq!(
            boundary_string.len(),
            16384,
            "String of exact pool length 16384 must have length 16384"
        );

        let large_string = generate_random_string(20_000);
        assert_eq!(
            large_string.len(),
            20_000,
            "Large string exceeding pool size must have length 20000"
        );

        let very_large_string = generate_random_string(50_000);
        assert_eq!(
            very_large_string.len(),
            50_000,
            "Large string of length 50000 must have length 50000"
        );

        Ok(())
    }

    #[test]
    fn zipfian_upper_bound_clamping() -> anyhow::Result<()> {
        let generator = ZipfianGenerator::new(10, 20);
        for _ in 0..50_000 {
            let value = generator.next_i64();
            assert!(
                (10..=20).contains(&value),
                "Value {} must strictly be in inclusive interval [10, 20]",
                value
            );
        }

        // Test next_i64_with_item_count boundary conditions
        assert_eq!(generator.next_i64_with_item_count(0), 0);
        assert_eq!(generator.next_i64_with_item_count(1), 0);

        for item_count in [2, 5, 10, 100, 10_000] {
            for _ in 0..10_000 {
                let dynamic_value = generator.next_i64_with_item_count(item_count);
                assert!(
                    (0..item_count).contains(&dynamic_value),
                    "Dynamic value {} must be in [0, {})",
                    dynamic_value,
                    item_count
                );
            }
        }

        Ok(())
    }

    #[test]
    fn compute_zeta_o1_speed_and_large_n() -> anyhow::Result<()> {
        let theta = DEFAULT_ZIPFIAN_CONSTANT;
        // Large N computation should execute instantaneously in O(1) time
        let start = Instant::now();
        let zeta_1m = ZipfianGenerator::compute_zeta(1_000_000, theta);
        let elapsed = start.elapsed();

        assert!(
            elapsed.as_millis() < 5,
            "compute_zeta for 1M items should execute in O(1) under 5ms, took {:?}",
            elapsed
        );
        assert!(
            zeta_1m > ZETAN_1K,
            "zeta(1M) = {} must be greater than zeta(1K) = {}",
            zeta_1m,
            ZETAN_1K
        );

        let zeta_10b = ZipfianGenerator::compute_zeta(10_000_000_000, theta);
        assert!(
            (zeta_10b - ZETAN_10B).abs() < 1e-3,
            "zeta(10B) = {} must match precomputed ZETAN_10B = {} within 1e-3",
            zeta_10b,
            ZETAN_10B
        );

        Ok(())
    }

    #[test]
    fn type_traits() {
        fn assert_send_sync_debug_clone<T: Send + Sync + Debug + Clone>() {}
        fn assert_send_sync_debug<T: Send + Sync + Debug>() {}

        assert_send_sync_debug_clone::<ZipfianGenerator>();
        assert_send_sync_debug_clone::<ScrambledZipfianGenerator>();
        assert_send_sync_debug::<SkewedLatestGenerator>();
    }
}
