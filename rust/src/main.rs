use clap::Parser;
use spanner_rust_benchmark::{Args, run_benchmark};

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt::init();
    let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();
    let args = Args::parse();
    run_benchmark(args).await
}
