import random

from google.cloud import spanner
from google.cloud.spanner_v1.database import Database

from .abstract_benchmark import AbstractBenchmark


class PointSelectBenchmark(AbstractBenchmark):
    """
    Implements a 1-to-1 parity Point Select performance workload for Python.
    Picks a random ID between min_id and max_id and executes an optimized point select query.
    """

    def get_benchmark_name(self) -> str:
        return "Point Select Benchmark"

    def get_benchmark_type(self) -> str:
        return "point-select"

    def execute_operation(
        self, database: Database, table_name: str, min_id: int, max_id: int
    ) -> None:
        """Executes a single point-select statement query under single-use snapshot read context."""
        # Pick random ID uniform across [min_id, max_id] (inclusive)
        random_id = random.randint(min_id, max_id)

        sql = f"SELECT * FROM {table_name} WHERE id = @id"

        # Allocate a single-use read-only snapshot context (parity with other languages)
        with database.snapshot() as snapshot:
            results = snapshot.execute_sql(
                sql,
                params={"id": random_id},
                param_types={"id": spanner.param_types.INT64},
            )

            # Fully iterate row results to consume data bytes, forcing decoding
            # and avoiding compiler/runtime dead-code elimination optimizations.
            for row in results:
                # Access the first item in row tuple
                _ = row[0]
