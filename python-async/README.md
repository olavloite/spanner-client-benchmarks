# Cloud Spanner Asynchronous Python Benchmarks

This directory contains the asynchronous Python implementation of the Cloud Spanner client benchmarks. It is designed to test the performance of the `google-cloud-spanner` asynchronous client (`google.cloud.spanner_v1.AsyncClient`) under highly concurrent Poisson process arrival workloads and closed-loop execution.

---

## Scenarios
The benchmark provides the standard workload scenarios. See the top-level [README](../README.md#implemented-benchmarks) for details.

---

## Configuration Options

The benchmark supports all standard options described in the top-level [README](../README.md#configuration-options).

Supported arguments here:
- `--project`, `--instance`, `--database`: (Required) Connection details.
- `--table`: (Required for standard workloads) Target database table name.
- `--tps`, `--threads`, `--num-rows`: Execution parameters.
- `--burst-factor`, `--burst-duration`, `--burst-fraction`: Bursty load configuration.
- `--workers`: Number of parallel worker processes.

---

## Features
1. **Native Asynchronous Client**: Built using `from google.cloud.spanner_v1 import AsyncClient` with full non-blocking `async`/`await` primitives and single multiplexed session semantics.
2. **Latest Source Integration**: Automatically clones `googleapis/google-cloud-python` into a temporary directory, installs the unreleased Spanner client package from source, and runs the benchmark against it.
3. **Isolated Virtual Environments**: Natively supports multi-stage Docker builds configured with Python virtual environments (`venv`), keeping the final runtime image extremely lightweight and clean.
4. **GIL & Event Loop Scheduling**: Uses a cooperative non-blocking `asyncio` Poisson scheduler and multi-process worker model with inter-process pipes to achieve maximum throughput and avoid Global Interpreter Lock contention.
5. **Mirror Resilience**: Bypasses internal VM package mirror restrictions by automatically routing package installations via PyPI (`--index-url https://pypi.org/simple`).

---

## Prerequisites
- **Python 3.10** or later
- Authenticated `gcloud` credentials

---

## Running the Benchmark

It is recommended to run the benchmark from the project root directory using the unified runner scripts:

```bash
# Run locally (compiles Spanner from source and executes point-select)
./run_benchmark_locally.sh python-async --project <PROJECT_ID> --instance <INSTANCE_ID> --database <DATABASE_ID> --duration 60s point-select --table <TABLE_NAME>
```
