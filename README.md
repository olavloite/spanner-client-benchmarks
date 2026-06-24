# Spanner Client Benchmarks

This project contains benchmarks for Cloud Spanner clients in various languages. The goal is to provide fair, apples-to-apples comparisons across different client libraries and programming languages, helping to track performance and spot regressions over time.

The benchmarks are built and run against the **most recent source code** of their corresponding client libraries, pulled directly from their respective upstream official repositories.

---

## Current State & Language Support

- 🟢 **Java**: Implemented.
- 🟢 **Go**: Implemented.
- 🟢 **Node.js**: Implemented.
- 🟢 **Python**: Implemented.
- 🟢 **Rust**: Implemented.

---

## Implemented Benchmarks

All benchmarks support the following workload scenarios:

### Point Query (`point-select`)
Executes a single row read based on a randomly selected primary key value using a query parameter. Measures raw read latency.

### Select and Update (`select-update`)
A read-modify-write scenario executed inside a Read-Write Transaction. Reads a row and updates its payload with a random string.

### Read Large Result Set (`read-large-result-set`)
Executes a query that generates a large result set with all supported data types, and iterates over the results to measure throughput and decoding performance.

### TPC-C Benchmark (`tpcc`)
Runs a closed-loop TPC-C benchmark against Spanner to measure performance under standardized transaction mixes (New-Order, Payment, Order-Status, Delivery, Stock-Level). 

> [!NOTE]
> Running the TPC-C benchmark requires an initialization phase using the `tpcc-init` subcommand (currently supported by the Java client runner) to create the schema and populate the test data. Once populated, any language client can execute the benchmark.

---

## Configuration Options

The following options are supported by the benchmark applications. While most languages support these as global options (specified before the subcommand) or subcommand options, the exact placement may vary slightly depending on the language's CLI library. Use `--help` on the specific client for exact usage.

### General Options

- `-p, --project`: (Required) Google Cloud Project ID.
- `-i, --instance`: (Required) Spanner Instance ID.
- `-d, --database`: (Required) Spanner Database ID.
- `-t, --table`: (Required for most scenarios) Target database table name (not required for `tpcc`).
- `--duration`: Duration of the benchmark (e.g., `60s`, `5m`, `inf` for infinite). Defaults to `inf`.
- `--for-alerting`: Marks the metrics for alerting pipelines. Defaults to `false`.
- `--host`: Custom Spanner endpoint URL override (e.g., for emulators).
- `--threads`: Number of parallel workers allowed (default: 100). *(Not supported for TPC-C, which uses `--clients` instead)*.
- `--tps`: Target transactions per second (default varies by scenario). *(Not supported for TPC-C, which runs closed-loop as fast as possible)*.
- `--num-rows`: Number of rows to generate or select from.
- `--load-type`: Workload generator load pattern. *(Not supported for TPC-C)*. Supported types are:
    - `steady`: Default. Steady Poisson load (constant target TPS).
    - `spiky`: Alternates randomly between quiet and burst states simulating a spiky load.
    - `gradual`: Generates a sine-wave shaped load pattern over a cycle.

### Spiky/Bursty Load Options (Only for `--load-type spiky`)

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

### Gradual/Sine Load Options (Only for `--load-type gradual`)

These options generate a sine-wave shaped load pattern where traffic varies periodically over a specified cycle duration:

- `--cycle-duration`: The duration of a full sine-wave cycle (e.g., `10m`, `1h`, `12h`).
    - *Default*: `1h`
- `--peak-factor`: The multiplier applied to the target `--tps` to determine the peak of the sine wave.
    - *How it works*: If target `--tps` is `100` and `--peak-factor` is `2.0`, the load will smoothly oscillate between a minimum of `0` TPS and a peak of `200` TPS.
    - *Default*: `2.0`

### Environment-only Configuration Variables

These variables can be set as environment variables before executing `./run_benchmark.sh` to customize deployment-specific resources or configurations:

- `BENCHMARK_TARGET`: Target platform for benchmark execution. Defaults to `gce`. Supported values are `gce` (Google Compute Engine Spot VMs) and `cloud-run` (Google Cloud Run Jobs).
- `REGION`: Target Google Cloud region for deploying the benchmark. Defaults to `europe-north1`.
- `CPU`: Number of vCPUs allocated to the benchmark task. Defaults to `2`. For GCE, this determines the machine type (e.g., `2` maps to `n2-standard-2`, `4` to `n2-standard-4`, `8` to `n2-standard-8`). For Cloud Run, this sets the task vCPU allocation.
- `MEMORY`: Memory size allocated to the task (only applicable for the `cloud-run` target). Defaults to `2Gi`.
- `SPANNER_DISABLE_BUILTIN_METRICS`: Set to `true` to disable client-side OpenTelemetry metrics emission inside the benchmark runner. Defaults to `false`.
- `POLLING_INTERVAL`: Cloud Build polling interval in seconds. Defaults to `30`.
- `SKIP_CLEANUP`: Set to `true` to skip running the automatic cleanup script (`cleanup_benchmarks.sh`) before deployment. Defaults to `false`.


---

## Project Structure

- [java/](java/): Java benchmark implementation.
- [go/](go/): Go benchmark implementation.
- [node/](node/): Node.js benchmark implementation.
- [python/](python/): Python benchmark implementation.
- [rust/](rust/): Rust benchmark implementation.
- [analyzer/](analyzer/): Benchmarks regression analyzer.

---

## Running the Benchmarks

Build and execution scripts are provided at the project root to simplify running benchmarks either locally for testing, or deployed to Google Cloud.

### 1. Local Execution
To build and test the benchmarks locally against the latest upstream client library:
```bash
./run_benchmark_locally.sh <go|java|node|python|rust> [options] [benchmark-type]
```

To run local builds against a specific upstream branch or commit hash:
```bash
# Example: Test experimental changes on Go client branch "feature/fast-decoder"
export CLIENT_BRANCH="feature/fast-decoder"
export USE_RELEASED_VERSION="false"
./run_benchmark_locally.sh go --project <PROJECT_ID> --instance <INSTANCE_ID> --database <DATABASE_ID> --table <TABLE_NAME> point-select --tps 100
```

### 2. Cloud Deployments (GCE by default, or Cloud Run)
Benchmarks are designed to run on GCE VM Spot instances for sustained, predictable performance tracking. They can also be deployed as Cloud Run Jobs. To package and deploy them to the cloud:
```bash
./run_benchmark.sh <go|java|node|python|rust>
```
This will:
- Pull the latest client library code from the official upstream repository.
- Build a lightweight Docker container via **Cloud Build**.
- Deploy and execute a **GCE Spot Instance VM** (or a **Cloud Run Job** if `BENCHMARK_TARGET=cloud-run` is set) configured with the required environment variables.

> [!TIP]
> You can customize execution parameters (e.g., `BENCHMARK_TARGET`, `PROJECT_ID`, `TPS`, `THREADS`, `DURATION`, etc.) by declaring environment variables before running the scripts.

### 3. Running Experimental Branch Builds in the Cloud

If you are testing experimental client library changes pushed to a specific GitHub branch, you can easily benchmark them by specifying `CLIENT_BRANCH` and `BENCHMARK_NAME`.

```bash
# Example: Test experimental changes on branch "feature/fast-decoder"
export CLIENT_BRANCH="feature/fast-decoder"
export BENCHMARK_NAME="exp-fast-decoder"
export DURATION="1h"
export BENCHMARK_TYPE="read-large-result-set"

./run_benchmark.sh go
```

This will:
- Clone the specific `feature/fast-decoder` branch from the upstream repository.
- Include the attribute `benchmark_name="exp-fast-decoder"` in all emitted OpenTelemetry metrics, allowing you to easily filter and compare your experiment in Google Cloud Monitoring.


### 4. Running TPC-C Benchmark

To run the TPC-C benchmark, you must first initialize the schema and populate the database with test data. The initialization is currently supported via the Java runner.

#### TPC-C Specific Options
- `--warehouses`: The scaling factor representing the number of simulated warehouses. Default is `1`. This controls the database sizing and data capacity. Note that the value used during benchmark execution (`tpcc`) must match or be smaller than the value used during database initialization (`tpcc-init`).
- `--clients`: The number of parallel closed-loop clients executing TPC-C transactions as fast as possible. Default is `10`. *(Only supported for `tpcc`, not `tpcc-init`)*.
- `--items`: The size of the item catalog. Default is `100000`. Must match between the initialization and execution runs.

**Step 1: Initialize TPC-C Schema and Data**
Initialization of the schema and initial data is performed using the Java runner.

*Deploy and execute via Cloud Run Job (Recommended):*
```bash
export BENCHMARK_TYPE="tpcc-init"
export WAREHOUSES=10
export ITEMS=100000
export PROJECT_ID="<PROJECT_ID>"
export INSTANCE_ID="<INSTANCE_ID>"
export DATABASE_ID="<DATABASE_ID>"

./run_benchmark.sh java
```

*Or run locally:*
```bash
./run_benchmark_locally.sh java --project <PROJECT_ID> --instance <INSTANCE_ID> --database <DATABASE_ID> tpcc-init --warehouses 10 --items 100000
```

**Step 2: Execute TPC-C Benchmark Run**
Once the database is initialized, run the closed-loop TPC-C benchmark in any of the supported languages (e.g., Go).

*Deploy and execute via Cloud Run Job (Recommended):*
```bash
export BENCHMARK_TYPE="tpcc"
export WAREHOUSES=10
export ITEMS=100000
export CLIENTS=20
export DURATION="1h"
export PROJECT_ID="<PROJECT_ID>"
export INSTANCE_ID="<INSTANCE_ID>"
export DATABASE_ID="<DATABASE_ID>"

./run_benchmark.sh go
```

*Or run locally:*
```bash
./run_benchmark_locally.sh go --project <PROJECT_ID> --instance <INSTANCE_ID> --database <DATABASE_ID> tpcc --warehouses 10 --clients 20 --items 100000
```

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

---

## Extending the ChatOps Bot

The conversational runner uses Gemini 2.5 Flash to orchestrate deployments directly from issue comments. If you are extending this repository, follow these guidelines to update the bot:

### 1. Adding a New Client Library or Benchmark Type
Do not modify the prompt instructions (`.github/scripts/bot_instructions.md`) directly. The bot's lists of valid values are dynamically injected. 
To add support:
1. Open [parse_chatops.py](file:///.github/scripts/parse_chatops.py).
2. Update the whitelists at the top of the file:
   - `SUPPORTED_CLIENTS` (e.g. add `"csharp"`)
   - `SUPPORTED_BENCHMARKS` (e.g. add `"tpch"`)
3. The next time the bot runs, it will automatically register the new values as valid choices.

### 2. Adding a New Benchmark Configuration Option
If you are adding a new parameter or flag (e.g., `--new-option`):
1. In [parse_chatops.py](file:///.github/scripts/parse_chatops.py):
   - Add the key to `RESPONSE_SCHEMA` (in the Python dict).
   - Add the appropriate sanitization regex check in `sanitize_run()`.
   - Add the key to the final returned dictionary of `sanitize_run()`.
2. In [.github/workflows/gemini-chatops.yml](file:///.github/workflows/gemini-chatops.yml):
   - Extract the value from the run dictionary in the parallel deployment step: `new_option=$(echo "$run" | jq -r '.new_option')`.
   - Export it as an environment variable in the execution block (e.g., `NEW_OPTION="$new_option"`).
3. In [run_benchmark.sh](file:///run_benchmark.sh):
   - Read the environment variable and append the option flag to the command execution line.



