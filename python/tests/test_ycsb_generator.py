import unittest

from src.benchmarks.ycsb.generator import (
    DEFAULT_ZIPFIAN_CONSTANT,
    ScrambledZipfianGenerator,
    SkewedLatestGenerator,
    ZipfianGenerator,
    compute_zeta,
    fnv_hash_64,
)
from src.benchmarks.ycsb.utils import build_key_name, generate_random_string


class TestYcsbGenerator(unittest.TestCase):
    def test_zipfian_boundary_conditions(self):
        gen1 = ZipfianGenerator(0, 0)
        for _ in range(100):
            self.assertEqual(gen1.next_value(), 0)

        gen2 = ZipfianGenerator(10, 11)
        for _ in range(100):
            val = gen2.next_value()
            self.assertTrue(10 <= val <= 11)

    def test_zipfian_bounds_clamping(self):
        gen = ZipfianGenerator(10, 20)
        for _ in range(1000):
            val = gen.next_value()
            self.assertTrue(10 <= val <= 20)

    def test_compute_zeta_continuity(self):
        zeta_exact = compute_zeta(1000, DEFAULT_ZIPFIAN_CONSTANT)
        zeta_approx = compute_zeta(1001, DEFAULT_ZIPFIAN_CONSTANT)
        diff = abs(zeta_approx - (zeta_exact + 1.0 / (1001**DEFAULT_ZIPFIAN_CONSTANT)))
        self.assertTrue(diff < 1e-4, f"Zeta continuity diverged: {diff}")

    def test_scrambled_zipfian_distribution(self):
        min_val = 100
        max_val = 200
        gen = ScrambledZipfianGenerator(min_val, max_val)
        counts = {}
        for _ in range(10000):
            val = gen.next_value()
            self.assertTrue(min_val <= val <= max_val)
            counts[val] = counts.get(val, 0) + 1

        self.assertTrue(
            len(counts) > 50,
            "Scrambled distribution should cover multiple values",
        )

    def test_skewed_latest_generator_skew(self):
        # Basis = 100,000 items
        seq = [100000]
        gen = SkewedLatestGenerator(lambda: seq[0])

        vals = [gen.next_value() for _ in range(5000)]
        for v in vals:
            self.assertTrue(
                0 <= v < 100000, f"Generated key {v} out of bounds [0, 100000)"
            )

        # Statistical check: majority of generated keys should be in the latest range
        latest_ratio = sum(1 for v in vals if v >= 90000) / len(vals)
        self.assertTrue(
            latest_ratio > 0.70,
            f"Expected >70% of keys in latest 10%, got {latest_ratio:.2%}",
        )

        # Dynamic increment check
        seq[0] = 200000
        val2 = gen.next_value()
        self.assertTrue(0 <= val2 < 200000)

    def test_fnv_hash_64_known_test_vectors(self):
        # Known test vectors matching Java, Go, Rust implementations
        self.assertEqual(fnv_hash_64(0), 6284781860667377211)
        self.assertEqual(fnv_hash_64(1), 8517097267634966620)
        self.assertEqual(fnv_hash_64(42), 55488592825689361)

        # Boundary checks for non-negative guarantees
        self.assertTrue(fnv_hash_64(-9223372036854775808) >= 0)
        self.assertTrue(fnv_hash_64(9223372036854775807) >= 0)
        self.assertTrue(fnv_hash_64(-1) >= 0)

        # Ensure always positive and never Long.MIN_VALUE
        for val in range(1000):
            h = fnv_hash_64(val)
            self.assertTrue(h >= 0, f"Hash for {val} was negative: {h}")

    def test_build_key_name(self):
        self.assertEqual(build_key_name(1, 12), "user000000000001")
        self.assertEqual(build_key_name(42, 0), "user42")
        self.assertEqual(build_key_name(42, -1), "user42")
        self.assertEqual(build_key_name(123456, 4), "user123456")

    def test_generate_random_string(self):
        self.assertEqual(generate_random_string(0), "")
        self.assertEqual(generate_random_string(-5), "")
        s100 = generate_random_string(100)
        self.assertEqual(len(s100), 100)
        s32k = generate_random_string(32768)
        self.assertEqual(len(s32k), 32768)


if __name__ == "__main__":
    unittest.main()
