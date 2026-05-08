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

These options configure the **2-State Markov-Modulated Poisson Process (MMPP)** to simulate spiky load. The load generator alternates randomly between a **Quiet** state and a **Burst** state. The arrivals in both states are still Poisson (random inter-arrival times), but the average rate changes.

- `--burst-factor`: The multiplier applied to the target `--tps` to determine the rate during a burst.
    - *How it works*: If your target `--tps` is `100`, and you set `--burst-factor` to `5.0`, the benchmark will generate traffic at a rate of `500` TPS during bursts.
    - *Default*: `1.0` (Steady load, no bursts).
- `--burst-duration`: The average duration of a single burst period in seconds.
    - *How it works*: `--burst-duration 2.0` means that once a burst starts, it will last for an average of 2 seconds before returning to the quiet state.
    - *Default*: `1.0`
- `--burst-fraction`: The fraction of the total time the benchmark spends in the burst state.
    - *How it works*: `--burst-fraction 0.1` means the benchmark will be in the burst state roughly 10% of the time and in the quiet state 90% of the time.
    - *Default*: `0.1`

#### How They Work Together (Examples)

The model guarantees that the **overall long-term average TPS matches the requested `--tps`**. To achieve this, traffic during the quiet state is automatically reduced to compensate for the bursts.

**Example 1: Occasional heavy spikes**
- `--tps 100`
- `--burst-factor 5.0` (Burst rate: 500 TPS)
- `--burst-fraction 0.1` (10% time in burst)
- *Result*: 10% of the time the rate is 500 TPS. To maintain a 100 TPS average, the rate during the remaining 90% of the time (Quiet state) drops to about `55` TPS. This simulates an application that is usually quiet but occasionally gets hit by a flood of requests.

**Example 2: Frequent small bursts**
- `--tps 100`
- `--burst-factor 2.0` (Burst rate: 200 TPS)
- `--burst-fraction 0.3` (30% time in burst)
- *Result*: 30% of the time the rate is 200 TPS. The remaining 70% of the time, the rate is about `57` TPS.

**Example 3: Simulating quiet periods (Lulls)**
- `--tps 100`
- `--burst-factor 0.5` (Burst rate is *lower* than average: 50 TPS)
- `--burst-fraction 0.2` (20% time in "burst" state, which is actually a lull)
- *Result*: 20% of the time the rate drops to 50 TPS. The remaining 80% of the time, the rate increases to about `112` TPS to compensate. This is useful for testing how the system recovers after a quiet period.

> [!WARNING]
> **Constraint**: To ensure the rate in the quiet state does not become negative, you must ensure that `burst-factor` $\le 1 / \text{burst-fraction}$. For example, if `burst-fraction` is `0.2`, the maximum `burst-factor` is `5.0`.


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


