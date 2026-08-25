from google.cloud import spanner
from google.cloud.spanner_v1.database import Database


def init_schema(database: Database, table_name: str, field_count: int = 10) -> None:
    """
    Initializes the YCSB table schema if it does not already exist.
    """
    if _table_exists(database, table_name):
        print(f"Table '{table_name}' already exists. Skipping DDL creation.")
        return

    ddl = generate_schema_ddl(table_name, field_count)
    print(f"Creating YCSB table '{table_name}' with {field_count} fields...")
    operation = database.update_ddl([ddl])
    operation.result()
    print(f"Table '{table_name}' created successfully.")


def generate_schema_ddl(table_name: str, field_count: int) -> str:
    """
    Generates the canonical Cloud Spanner CREATE TABLE DDL statement for YCSB.
    Matches the schema used in Java, Rust, and Go implementations.
    """
    field_lines = [f"  field{i} STRING(MAX)" for i in range(field_count)]
    fields_sql = ",\n".join(field_lines)
    return (
        f"CREATE TABLE IF NOT EXISTS {table_name} (\n"
        f"  id STRING(MAX),\n"
        f"{fields_sql}\n"
        f") PRIMARY KEY(id)"
    )


def _table_exists(database: Database, table_name: str) -> bool:
    """Checks whether the specified table exists in the Spanner database."""
    query = (
        "SELECT 1 FROM INFORMATION_SCHEMA.TABLES "
        "WHERE TABLE_SCHEMA = '' AND TABLE_NAME = @tableName"
    )
    try:
        with database.snapshot() as snapshot:
            results = snapshot.execute_sql(
                query,
                params={"tableName": table_name},
                param_types={"tableName": spanner.param_types.STRING},
            )
            for _ in results:
                return True
            return False
    except Exception:
        return False


# Module-level alias for backward compatibility
table_exists = _table_exists
