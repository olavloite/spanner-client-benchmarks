import unittest

from src.benchmarks.ycsb.populate import _compute_partition_ranges
from src.benchmarks.ycsb.schema import generate_schema_ddl
from src.benchmarks.ycsb.workload import (
    KeyDistribution,
    Operation,
    Workload,
    parse_distribution,
    parse_workload,
)


class TestYcsbWorkload(unittest.TestCase):
    def test_workload_distributions(self):
        iterations = 50000
        test_cases = [
            (Workload.A, 0.50, 0.50, 0.00, 0.00, 0.00),
            (Workload.B, 0.95, 0.05, 0.00, 0.00, 0.00),
            (Workload.C, 1.00, 0.00, 0.00, 0.00, 0.00),
            (Workload.D, 0.95, 0.00, 0.05, 0.00, 0.00),
            (Workload.E, 0.00, 0.00, 0.05, 0.95, 0.00),
            (Workload.F, 0.50, 0.00, 0.00, 0.00, 0.50),
        ]

        for wl, exp_read, exp_up, exp_ins, exp_scan, exp_rmw in test_cases:
            counts = {op: 0 for op in Operation}
            for _ in range(iterations):
                op = wl.next_operation()
                counts[op] += 1

            self._assert_ratio(
                wl, "READ", counts[Operation.READ] / iterations, exp_read
            )
            self._assert_ratio(
                wl, "UPDATE", counts[Operation.UPDATE] / iterations, exp_up
            )
            self._assert_ratio(
                wl, "INSERT", counts[Operation.INSERT] / iterations, exp_ins
            )
            self._assert_ratio(
                wl, "SCAN", counts[Operation.SCAN] / iterations, exp_scan
            )
            self._assert_ratio(
                wl, "RMW", counts[Operation.READ_MODIFY_WRITE] / iterations, exp_rmw
            )

    def _assert_ratio(self, workload, op_name, actual, expected):
        if expected == 0.0 and actual > 0.0:
            self.fail(
                f"[{workload}] Unexpected {op_name} operations (got {actual}, expected 0)"
            )
        if expected > 0.0:
            diff = abs(actual - expected)
            self.assertTrue(
                diff <= 0.02,
                f"[{workload}] Ratio for {op_name} diverged: got {actual}, expected {expected}",
            )

    def test_parse_workload(self):
        valid = ["A", "b", " C ", "D", "e", "F"]
        expected = [
            Workload.A,
            Workload.B,
            Workload.C,
            WorkloadD := Workload.D,
            Workload.E,
            Workload.F,
        ]
        for v, exp in zip(valid, expected):
            self.assertEqual(parse_workload(v), exp)

        for inv in ["G", "X", "", "invalid"]:
            with self.assertRaises(ValueError):
                parse_workload(inv)

    def test_parse_distribution(self):
        valid = {
            "scrambled-zipfian": KeyDistribution.SCRAMBLED_ZIPFIAN,
            "SCRAMBLEDZIPFIAN": KeyDistribution.SCRAMBLED_ZIPFIAN,
            "scrambled_zipfian": KeyDistribution.SCRAMBLED_ZIPFIAN,
            "zipfian": KeyDistribution.ZIPFIAN,
            "ZIPFIAN": KeyDistribution.ZIPFIAN,
            "uniform": KeyDistribution.UNIFORM,
            "UNIFORM": KeyDistribution.UNIFORM,
        }
        for s, exp in valid.items():
            self.assertEqual(parse_distribution(s), exp)

        for inv in ["latest", "gaussian", "", "unknown"]:
            with self.assertRaises(ValueError):
                parse_distribution(inv)

    def test_generate_schema_ddl(self):
        ddl = generate_schema_ddl("usertable", 3)
        self.assertIn("CREATE TABLE IF NOT EXISTS usertable", ddl)
        self.assertIn("id STRING(MAX)", ddl)
        self.assertIn("field0 STRING(MAX)", ddl)
        self.assertIn("field1 STRING(MAX)", ddl)
        self.assertIn("field2 STRING(MAX)", ddl)
        self.assertNotIn("field3 STRING(MAX)", ddl)
        self.assertIn("PRIMARY KEY(id)", ddl)

    def test_compute_partition_ranges(self):
        ranges = _compute_partition_ranges(100, 4)
        self.assertEqual(ranges, [(0, 25), (25, 50), (50, 75), (75, 100)])

        ranges_uneven = _compute_partition_ranges(10, 3)
        self.assertEqual(ranges_uneven, [(0, 4), (4, 7), (7, 10)])

        ranges_more_threads = _compute_partition_ranges(2, 5)
        self.assertEqual(ranges_more_threads, [(0, 1), (1, 2)])


if __name__ == "__main__":
    unittest.main()
