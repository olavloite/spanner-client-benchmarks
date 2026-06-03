use futures::FutureExt;
use futures::future::BoxFuture;
use google_cloud_spanner::client::DatabaseClient;
use google_cloud_spanner::result::{ResultSet, Row};
use google_cloud_spanner::statement::Statement;
use opentelemetry::KeyValue;
use opentelemetry::metrics::Histogram;
use std::hint::black_box;
use std::time::Instant;

pub fn execute_read_large_result_set(
    client: DatabaseClient,
    num_rows: i64,
    histogram: Histogram<f64>,
    attributes: Vec<KeyValue>,
) -> BoxFuture<'static, anyhow::Result<()>> {
    async move {
        let sql = r#"SELECT
          MOD(FARM_FINGERPRINT(GENERATE_UUID()), 2) = 0 AS random_bool,
          CAST(GENERATE_UUID() AS BYTES) AS random_bytes,
          DATE_FROM_UNIX_DATE(ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 2932896))) AS random_date,
          CAST(FARM_FINGERPRINT(GENERATE_UUID()) / FARM_FINGERPRINT(GENERATE_UUID()) AS FLOAT32) AS random_float32,
          CAST(FARM_FINGERPRINT(GENERATE_UUID()) / FARM_FINGERPRINT(GENERATE_UUID()) AS FLOAT64) AS random_float64,
          TO_JSON('{"key": "' || GENERATE_UUID() || '"}') AS random_json,
          FARM_FINGERPRINT(GENERATE_UUID()) AS random_int64,
          GENERATE_UUID() AS random_string,
          TIMESTAMP_MICROS(ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 1230219000000000))) AS random_timestamp
        FROM UNNEST(GENERATE_ARRAY(1, @num_rows)) AS n"#;

        let statement = Statement::builder(sql)
            .add_param("num_rows", &num_rows)
            .build();

        let transaction = client.single_use().build();
        let mut result_set: ResultSet = transaction.execute_query(statement).await?;
        if let Some(row) = result_set.next().await.transpose()? {
            decode_row(&row);
        } else {
            return Ok(());
        }

        // INTENTIONAL: We intentionally exclude the initial query execution and the first row fetch
        // to measure purely the iteration and decoding latency of the remaining rows. Do not change this.
        let start = Instant::now();
        while let Some(row) = result_set.next().await.transpose()? {
            decode_row(&row);
        }
        let duration_us = start.elapsed().as_micros() as f64;
        histogram.record(duration_us, &attributes);

        Ok(())
    }
    .boxed()
}

fn decode_row(row: &Row) {
    let _: bool = black_box(row.get(0));
    let _: Vec<u8> = black_box(row.get(1));
    let _: time::Date = black_box(row.get(2));
    let _: f32 = black_box(row.get(3));
    let _: f64 = black_box(row.get(4));
    let _: String = black_box(row.get(5));
    let _: i64 = black_box(row.get(6));
    let _: String = black_box(row.get(7));
    let _: time::OffsetDateTime = black_box(row.get(8));
}
