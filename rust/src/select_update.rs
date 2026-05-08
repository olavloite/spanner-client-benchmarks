use google_cloud_spanner::client::{DatabaseClient, Statement};
use rand::RngExt;
use futures::future::BoxFuture;
use futures::FutureExt;

pub fn execute_select_update(
    client: DatabaseClient,
    table: String,
    min_id: i64,
    max_id: i64,
) -> BoxFuture<'static, anyhow::Result<()>> {
    async move {
        let runner = client.read_write_transaction().build().await?;
        runner.run(async move |transaction| {
            let random_id = rand::random_range(min_id..=max_id);

            let sql = format!("SELECT id FROM {} WHERE id = @id", table);
            let statement = Statement::builder(&sql)
                .add_param("id", &random_id)
                .build();

            let mut result_set = transaction.execute_query(statement).await?;
            let exists = result_set.next().await.transpose()?.is_some();
            drop(result_set); // release results before update

            let random_value: String = {
                let mut rng = rand::rng();
                let length = rand::random_range(75..=150);
                (0..length)
                    .map(|_| rng.sample(rand::distr::Alphanumeric) as char)
                    .collect()
            };

            if exists {
                let update_sql = format!("UPDATE {} SET value = @value WHERE id = @id", table);
                let update_statement = Statement::builder(&update_sql)
                    .add_param("value", &random_value)
                    .add_param("id", &random_id)
                    .build();
                transaction.execute_update(update_statement).await?;
            } else {
                let insert_sql = format!("INSERT INTO {} (id, value) VALUES (@id, @value)", table);
                let insert_statement = Statement::builder(&insert_sql)
                    .add_param("id", &random_id)
                    .add_param("value", &random_value)
                    .build();
                transaction.execute_update(insert_statement).await?;
            }
            Ok(())
        }).await?;

        Ok(())
    }
    .boxed()
}
