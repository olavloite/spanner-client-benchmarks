import random
import string
from google.cloud import spanner
from google.cloud.spanner_v1.database import Database
from google.cloud.spanner_v1.transaction import Transaction
from .abstract_benchmark import AbstractBenchmark

class SelectAndUpdateBenchmark(AbstractBenchmark):
    """
    Implements a 1-to-1 parity Select and Update workload inside a Read-Write Transaction in Python.
    """

    def get_benchmark_name(self) -> str:
        return "Select and Update Benchmark"

    def get_benchmark_type(self) -> str:
        return "select-update"

    def execute_operation(
        self, database: Database, table_name: str, min_id: int, max_id: int
    ) -> None:
        """Executes a single read-modify-write transaction sequence."""
        random_id = random.randint(min_id, max_id)

        # The transaction callback logic that Spanner will call and retry if a conflict occurs
        def transaction_callback(transaction: Transaction) -> None:
            select_sql = f"SELECT id FROM {table_name} WHERE id = @id"
            
            # 1. Execute read sql query statement inside transaction context
            results = transaction.execute_sql(
                select_sql,
                params={"id": random_id},
                param_types={"id": spanner.param_types.INT64},
            )

            # Check row presence
            exists = False
            for _ in results:
                exists = True
                break # single row point check, we can skip further fetching

            # Generate random alphanumeric string between 75 and 150 characters (parity constraint)
            random_length = random.randint(75, 150)
            random_value = self._generate_random_string(random_length)

            # 2. Conditionally dispatch Insert or Update DML statement
            if exists:
                dml_sql = f"UPDATE {table_name} SET value = @value WHERE id = @id"
            else:
                dml_sql = f"INSERT INTO {table_name} (id, value) VALUES (@id, @value)"

            transaction.execute_update(
                dml_sql,
                params={"id": random_id, "value": random_value},
                param_types={
                    "id": spanner.param_types.INT64,
                    "value": spanner.param_types.STRING,
                },
            )

        # Execute the callback via the client library's transaction runner framework
        database.run_in_transaction(transaction_callback)

    def _generate_random_string(self, length: int) -> str:
        """Generates a random alphanumeric string using the exact alphabet specs."""
        alphabet = string.ascii_letters + string.digits # ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789
        return "".join(random.choice(alphabet) for _ in range(length))
