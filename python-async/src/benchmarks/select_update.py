import random
import string

from google.cloud import spanner
from google.cloud.spanner_v1.database import Database
from google.cloud.spanner_v1.transaction import Transaction

from .abstract_benchmark import AbstractBenchmark


def _generate_random_string(length: int) -> str:
    """Generates a random alphanumeric string using the exact alphabet specs."""
    alphabet = string.ascii_letters + string.digits
    return "".join(random.choice(alphabet) for _ in range(length))


async def execute_select_and_update(
    database: Database, table_name: str, min_id: int, max_id: int
) -> None:
    """Executes a single read-modify-write transaction sequence asynchronously."""
    random_id = random.randint(min_id, max_id)

    async def transaction_callback(transaction: Transaction) -> None:
        select_sql = f"SELECT id FROM {table_name} WHERE id = @id"

        results = await transaction.execute_sql(
            select_sql,
            params={"id": random_id},
            param_types={"id": spanner.param_types.INT64},
        )

        exists = False
        async for _ in results:
            exists = True
            break

        random_length = random.randint(75, 150)
        random_value = _generate_random_string(random_length)

        if exists:
            dml_sql = f"UPDATE {table_name} SET value = @value WHERE id = @id"
        else:
            dml_sql = f"INSERT INTO {table_name} (id, value) VALUES (@id, @value)"

        await transaction.execute_update(
            dml_sql,
            params={"id": random_id, "value": random_value},
            param_types={
                "id": spanner.param_types.INT64,
                "value": spanner.param_types.STRING,
            },
        )

    await database.run_in_transaction(transaction_callback)


class SelectAndUpdateBenchmark(AbstractBenchmark):
    """
    Implements a 1-to-1 parity Select and Update workload inside a Read-Write Transaction in async Python.
    """

    def get_benchmark_name(self) -> str:
        return "Select and Update Benchmark"

    def get_benchmark_type(self) -> str:
        return "select-update"

    async def execute_operation(
        self, database: Database, table_name: str, min_id: int, max_id: int
    ) -> None:
        await execute_select_and_update(database, table_name, min_id, max_id)
