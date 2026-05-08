# Cloud Spanner Go Benchmarks

This directory contains the Go implementation of the Cloud Spanner client benchmarks.

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
1. **Hermetic Builds**: The default preset script builds natively against the newest local changes of the Google Cloud Go Client automatically by cloning it into a temporary directory.
2. **Standard Opt-out option**: Opt-out of source builds and compile with the released package version via `USE_RELEASED_VERSION=true`.

---

## Prerequisites
- **Go 1.24** or later
- Authenticated `gcloud` credentials

---

## Running the benchmark
Running individual language benchmark binaries directly is supported but it is recommended to use the unified scripts placed at the project root folder for automated setup preset:

```bash
# From project root folder
./run_benchmark_locally.sh go --project <PROJECT_ID> --instance <INSTANCE_ID> --database <DATABASE_ID> --duration 60s point-select --table <TABLE_NAME>
```
