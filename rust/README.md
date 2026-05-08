# Cloud Spanner Rust Benchmarks

This directory contains the Rust implementation of the Cloud Spanner client benchmarks.

---

## Scenarios
The benchmark provides three scenarios:
- **`point-select`**: Executes a single row read based on a randomly selected primary key value.
- **`select-update`**: Executes a read-write transaction reading a single row and updating its value, with built-in abort retry capability.
- **`read-large-result-set`**: Dynamically generates a large dataset on Spanner and decodes all types of columns (integers, floats, JSON, dates, timestamps, etc.) to evaluate stream iteration throughput.

---

## Features
1. **Direct Git Dependency Integration**: Compiles directly against the unreleased Spanner crate from the default branch of the `google-cloud-rust` monorepo, natively resolving workspace path dependencies.
2. **High Concurrency Concurrency Control**: Leverages high-performance tokio semaphores to enforce concurrency limits and backpressure without memory leaks or task channel overhead.

---

## Prerequisites
- **Rust 1.83** or later
- Authenticated `gcloud` credentials

---

## Running the benchmark
To execute a local validation or benchmarking run:

```bash
# Build the release binary
cargo build --release

# Execute point-select benchmark directly
./target/release/spanner-rust-benchmark \
  --project <PROJECT_ID> \
  --instance <INSTANCE_ID> \
  --database <DATABASE_ID> \
  --table <TABLE_NAME> \
  --duration 60s \
  --threads 100 \
  point-select --tps 100
```
