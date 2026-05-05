# Cloud Spanner Python Benchmarks

This directory contains the Python implementation of the Cloud Spanner client benchmarks. It is designed to test the performance of the `google-cloud-spanner` client library under highly concurrent Poisson process arrival workloads.

---

## Scenarios
The benchmark provides two workloads:
- **`point-select`**: Executes an optimized single-row point-select query (`SELECT * FROM {table} WHERE id = @id`) under a single-use snapshot read context.
- **`select-update`**: Executes a read-modify-write sequence within a Read-Write transaction, updating a payload column with a random alphanumeric string.

---

## Features
1. **Latest Source Integration**: Automatically clones `googleapis/google-cloud-python` into a temporary directory, installs the unreleased Spanner client package from source, and runs the benchmark against it.
2. **Isolated Virtual Environments**: Natively supports multi-stage Docker builds configured with Python virtual environments (`venv`), keeping the final runtime image extremely lightweight and clean.
3. **GIL-Contention Mitigation**: Implements an adaptive sleep scheduler. Instead of polling every `100us` (which thrashes the Global Interpreter Lock), it calculates the remaining duration and sleeps for the exact interval when arrivals are far in the future. This allows Python interpreter threads to sleep properly, drastically reducing CPU overhead and context switching.
4. **Mirror Resilience**: Bypasses internal VM package mirror restrictions by automatically routing package installations via PyPI (`--index-url https://pypi.org/simple`).

---

## Prerequisites
- **Python 3.10** or later
- Authenticated `gcloud` credentials

---

## Running the Benchmark

It is recommended to run the benchmark from the project root directory using the unified runner scripts:

```bash
# Run locally (compiles Spanner from source and executes point-select)
./run_benchmark_locally.sh python --project <PROJECT_ID> --instance <INSTANCE_ID> --database <DATABASE_ID> --duration 60s point-select --table <TABLE_NAME>
```
