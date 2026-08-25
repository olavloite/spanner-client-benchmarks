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

use google_cloud_lro::Poller;
use google_cloud_spanner::client::DatabaseClient;
use google_cloud_spanner::result::ResultSet;
use google_cloud_spanner::statement::Statement;
use google_cloud_spanner_admin_database_v1::client::DatabaseAdmin;

/// Creates or verifies the existence of the YCSB table in Cloud Spanner via DatabaseAdmin DDL statements.
#[allow(clippy::too_many_arguments)]
pub async fn create_table(
    client: &DatabaseClient,
    project_id: &str,
    instance_id: &str,
    database_id: &str,
    table_name: &str,
    field_count: usize,
    host: Option<String>,
    mock: bool,
) -> anyhow::Result<()> {
    if table_exists(client, table_name).await.unwrap_or(false) {
        println!(
            "Table {} already exists. Skipping DDL creation.",
            table_name
        );
        return Ok(());
    }

    let ddl = generate_ddl(table_name, field_count);
    println!("Applying DDL for YCSB table: {}...", table_name);

    if mock {
        println!("Mock environment detected: skipping remote Spanner Admin DDL call.");
        return Ok(());
    }

    let mut admin_builder = DatabaseAdmin::builder();
    if let Some(ref custom_host) = host {
        admin_builder = admin_builder.with_endpoint(custom_host);
    }
    let admin_client = admin_builder.build().await?;

    let database_path = format!(
        "projects/{}/instances/{}/databases/{}",
        project_id, instance_id, database_id
    );

    admin_client
        .update_database_ddl()
        .set_database(database_path)
        .set_statements(vec![ddl])
        .poller()
        .until_done()
        .await?;
    println!("Successfully created/verified table: {}", table_name);
    Ok(())
}

/// Generates standard Cloud Spanner DDL for a YCSB table with a primary key column `id`
/// and the requested number of `field0`..`fieldN` `STRING(MAX)` columns.
pub(crate) fn generate_ddl(table_name: &str, field_count: usize) -> String {
    let mut builder = format!(
        "CREATE TABLE IF NOT EXISTS {} (\n    id STRING(MAX),\n",
        table_name
    );
    for index in 0..field_count {
        builder.push_str(&format!("    field{} STRING(MAX),\n", index));
    }
    builder.push_str(") PRIMARY KEY(id)");
    builder
}

/// Checks whether a given table exists in the target database using `INFORMATION_SCHEMA.TABLES`.
async fn table_exists(client: &DatabaseClient, table_name: &str) -> anyhow::Result<bool> {
    let sql = "SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '' AND TABLE_NAME = @tableName";
    let statement = Statement::builder(sql)
        .add_param("tableName", &table_name)
        .build();

    let transaction = client.single_use().build();
    let mut result_set: ResultSet = transaction.execute_query(statement).await?;
    let exists = result_set.next().await.transpose()?.is_some();
    Ok(exists)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn schema_ddl_generation() -> anyhow::Result<()> {
        let ddl = generate_ddl("usertable", 3);
        assert!(
            ddl.starts_with("CREATE TABLE IF NOT EXISTS usertable ("),
            "DDL should start with CREATE TABLE IF NOT EXISTS usertable"
        );
        assert!(
            ddl.contains("id STRING(MAX),"),
            "DDL must contain primary key id column"
        );
        assert!(
            ddl.contains("field0 STRING(MAX),"),
            "DDL must contain field0 column"
        );
        assert!(
            ddl.contains("field1 STRING(MAX),"),
            "DDL must contain field1 column"
        );
        assert!(
            ddl.contains("field2 STRING(MAX),"),
            "DDL must contain field2 column"
        );
        assert!(
            !ddl.contains("field3 STRING(MAX)"),
            "DDL must not contain field3 column when field_count is 3"
        );
        assert!(
            ddl.ends_with(") PRIMARY KEY(id)"),
            "DDL must specify PRIMARY KEY(id)"
        );
        Ok(())
    }
}
