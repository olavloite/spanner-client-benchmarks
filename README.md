# Spanner Client Benchmarks

This project contains benchmarks for Cloud Spanner clients in various languages. The goal is to provide fair, apples-to-apples comparisons across different client libraries and programming languages, helping to track performance and spot regressions over time.

The benchmarks are built and run against the **most recent source code** of their corresponding client libraries, pulled directly from their respective upstream official repositories.

---

## Current State & Language Support

- 🟢 **Java**: Implemented.
- 🟢 **Go**: Implemented.
- 🟢 **Node.js**: Implemented.
- 🟢 **Python**: Implemented.

---

## Implemented Benchmarks

All benchmarks support the following workload scenarios:

### Point Query (`point-select`)
Executes a single row read based on a randomly selected primary key value using a query parameter. Measures raw read latency.

### Select and Update (`select-update`)
A read-modify-write scenario executed inside a Read-Write Transaction. Reads a row and updates its payload with a random string.

### Read Large Result Set (`read-large-result-set`)
Executes a query that generates a large result set with all supported data types, and iterates over the results to measure throughput and decoding performance.

---

## Configuration Options

The following options are supported by the benchmark applications. While most languages support these as global options (specified before the subcommand) or subcommand options, the exact placement may vary slightly depending on the language's CLI library. Use `--help` on the specific client for exact usage.

### General Options

- `-p, --project`: (Required) Google Cloud Project ID.
- `-i, --instance`: (Required) Spanner Instance ID.
- `-d, --database`: (Required) Spanner Database ID.
- `-t, --table`: (Required for most scenarios) Target database table name.
- `--duration`: Duration of the benchmark (e.g., `60s`, `5m`, `inf` for infinite). Defaults to `inf`.
- `--for-alerting`: Marks the metrics for alerting pipelines. Defaults to `false`.
- `--host`: Custom Spanner endpoint URL override (e.g., for emulators).
- `--threads`: Number of parallel workers allowed (default: 100).
- `--tps`: Target transactions per second (default varies by scenario).
- `--num-rows`: Number of rows to generate or select from.

### Bursty Load Options

These options configure the 2-State Markov-Modulated Poisson Process (MMPP) to simulate spiky load:

- `--burst-factor`: Ratio of burst rate to average rate. A value of `1.0` means steady load (default).
- `--burst-duration`: Average duration of a burst in seconds (default: 1.0).
- `--burst-fraction`: Fraction of total time spent in the burst state (default: 0.1).

---

## Project Structure

- [java/](java/): Java benchmark implementation.
- [go/](go/): Go benchmark implementation.
- [node/](node/): Node.js benchmark implementation.
- [python/](python/): Python benchmark implementation.
- [analyzer/](analyzer/): Benchmarks regression analyzer.

---

## Running the Benchmarks

Build and execution scripts are provided at the project root to simplify running benchmarks either locally for testing, or deployed to Google Cloud.

### 1. Local Execution
To build and test the benchmarks locally against the latest upstream client library:
```bash
./run_benchmark_locally.sh <go|java|node|python> [options] [benchmark-type]
```

### 2. Cloud Run Jobs
Benchmarks are designed to run natively as Cloud Run Jobs for sustained performance tracking. To package and deploy them to the cloud:
```bash
./run_benchmark.sh <go|java|node|python>
```
This will:
- Pull the latest client library code from the official upstream repository.
- Build a lightweight Docker container via **Cloud Build**.
- Deploy and execute a **Cloud Run Job** configured with the required environment variables.

> [!TIP]
> You can customize execution parameters (e.g., `PROJECT_ID`, `TPS`, `THREADS`, `DURATION`, etc.) by declaring environment variables before running the scripts.

---

## Automated Cleanup
Prior to launching sustained benchmarks, old Cloud Run jobs and obsolete containers are automatically purged. 
You can also run this manually at any time:
```bash
./cleanup_benchmarks.sh
```
To skip auto cleanup in the standard runner, pass `SKIP_CLEANUP=true` to the environment.

---

## Regression Analysis Tool
The project includes a standalone analysis tool located in [analyzer/](analyzer/) that queries **Cloud Monitoring** metrics to detect performance regressions automatically. 

It compares current percentile latency (`P50`, `P99`) against predefined baselines (1-day and 7-day averages) and can trigger exit code alerts if standard thresholds are violated.

You can build and run the analyzer using Maven:
```bash
cd analyzer
mvn package
java -jar target/spanner-performance-analyzer-1.0-SNAPSHOT-jar-with-dependencies.jar -p <PROJECT_ID> --client java-client
```


