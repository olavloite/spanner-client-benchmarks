use google_cloud_spanner::client::{DatabaseClient, Statement};
use futures::future::BoxFuture;
use futures::FutureExt;

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
        let mut result_set = transaction.execute_query(statement).await?;
        while let Some(row) = result_set.next().await.transpose()? {
            let _id: i64 = row.get(0);
        }
        Ok(())
    }
    .boxed()
}
