use google_cloud_spanner::client::DatabaseClient;
use google_cloud_spanner::statement::Statement;
use google_cloud_spanner::result::ResultSet;
use futures::future::BoxFuture;
use futures::FutureExt;
use std::hint::black_box;

pub fn execute_point_select(
    client: DatabaseClient,
    table: String,
    min_id: i64,
    max_id: i64,
) -> BoxFuture<'static, anyhow::Result<()>> {
    async move {
        let random_id = rand::random_range(min_id..=max_id);
        let sql = format!("SELECT id, value FROM {} WHERE id = @id", table);
        let statement = Statement::builder(&sql)
            .add_param("id", &random_id)
            .build();

        let transaction = client.single_use().build();
        let mut result_set: ResultSet = transaction.execute_query(statement).await?;
        while let Some(row) = result_set.next().await.transpose()? {
            let _: i64 = black_box(row.get(0_usize));
            let _: String = black_box(row.get(1_usize));
        }
        Ok(())
    }
    .boxed()
}
