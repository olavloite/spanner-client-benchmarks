# Spanner Client Benchmarks

This project contains benchmarks for Cloud Spanner clients in various languages. The goal is to provide fair, apples-to-apples comparisons across different client libraries and programming languages, helping to track performance and spot regressions over time.

The benchmarks are built and run against the **most recent source code** of their corresponding client libraries, pulled directly from their respective upstream official repositories.

---

## Current State & Language Support

- 🟢 **Java**: Implemented.
- 🟢 **Go**: Implemented.
- 🟡 **Python**: Pending implementation.
- 🟡 **Node.js**: Pending implementation.

---

## Implemented Benchmarks

### Point Query (`point-select`)
A simple point query selecting one row based on a randomly selected primary key value using a query parameter.

### Select and Update (`select-update`)
A benchmark that selects a row and immediately updates it within a transaction.

---

## Project Structure

- [java/](java/): Java benchmark implementation.
- [go/](go/): Go benchmark implementation.
- [python/](python/): Python benchmark implementation scaffold.
- [node/](node/): Node.js benchmark implementation scaffold.
- [analyzer/](analyzer/): Benchmarks regression analyzer.

---

## Running the Benchmarks

Build and execution scripts are provided at the project root to simplify running benchmarks either locally for testing, or deployed to Google Cloud.

### 1. Local Execution
To build and test the benchmarks locally against the latest upstream client library:
```bash
./run_benchmark_locally.sh <go|java> [options] <benchmark-type>
```

### 2. Cloud Run Jobs
Benchmarks are designed to run natively as Cloud Run Jobs for sustained performance tracking. To package and deploy them to the cloud:
```bash
./run_benchmark.sh <go|java>
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


