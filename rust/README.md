# Cloud Spanner Rust Benchmarks

This directory contains the Rust implementation of the Cloud Spanner client benchmarks.

---

## Scenarios
The benchmark provides the standard workload scenarios. See the top-level [README](../README.md#implemented-benchmarks) for details.

---

## Configuration Options

The benchmark supports all standard options described in the top-level [README](../README.md#configuration-options).

Supported arguments here:
- `--project`, `--instance`, `--database`: (Required) Connection details.
- `--table`: (Required) Target database table name.
- `--tps`, `--threads`, `--num-rows`: Execution parameters.
- `--burst-factor`, `--burst-duration`, `--burst-fraction`: Bursty load configuration.

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
