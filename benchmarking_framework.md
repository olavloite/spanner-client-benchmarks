# Spanner Client Benchmarking Framework

This document provides a technical description of the multi-language performance benchmarking framework designed for Spanner client libraries.

---

## 1. Introduction

This benchmarking framework is designed to measure, compare, and track the performance of Spanner client libraries across different programming languages under standardized conditions. 

The framework is built on **three core pillars**:
* **Compiles Against Latest Source**: Instead of relying solely on stable published packages, the framework dynamically pulls, compiles, and links against the latest unreleased commits from the official upstream client library repositories. This allows developers to detect performance regressions before they are released to the public.
* **Comparability Across Languages**: All language implementations share identical workload scheduling logic (using Poisson process inter-arrival generation), identical metric definitions, and identical execution resource profiles, ensuring comparable results across different languages.
* **Comparability Over Time**: The benchmarks run as scheduled daily pipelines, exporting metrics to Google Cloud Monitoring. This provides a historical performance baseline to track how changes to both Spanner and the client libraries impact latency and throughput over weeks and months.

---

## 2. Walkthrough Example: The Java Workflow

To understand how the framework automates builds against latest source code and deploys to the cloud, we can examine the **Java build and deployment workflow**:

### Upstream Dependency Compilation
When a run is initiated, the local build script [java/build_app.sh](java/build_app.sh):
1. Creates a temporary directory.
2. Performs a partial clone (using `--filter=blob:none`) of the `google-cloud-java` repository.
3. Compiles and installs only the required modules (`grpc-gcp-java` and `java-spanner/google-cloud-spanner` along with their dependent modules) into the local Maven cache (`~/.m2/repository`), avoiding compiling the entire repository.
4. Evaluates the version of the freshly built Spanner client library and dynamically updates the benchmark's `pom.xml` dependency versions.
5. Compiles the benchmark application against the locally built dependencies, producing the final executable benchmark JAR.

### Containerization
The benchmark's [java/Dockerfile](java/Dockerfile) leverages a **multi-stage build**:
- **Build Stage**: Pulls a full Maven image, runs the `build_app.sh` script, downloads the latest Spanner source code, compiles dependencies, and builds the final JAR inside the container.
- **Runtime Stage**: Pulls a lightweight JRE image, copies only the final compiled JAR and libraries from the build stage, and sets the entry point.

### Job Deployment and Execution
When executing `./run_benchmark.sh java`:
1. The workspace context is uploaded to **Google Cloud Build** via `gcloud builds submit`.
2. Cloud Build compiles the source code and builds the Docker image inside Artifact Registry.
3. The runner deploys the image to the cloud. By default, it provisions a dedicated **GCE Spot Instance VM** running the benchmark container (optimized with Core 0 CPU affinity exclusion for performance predictability). Alternatively, it can deploy and execute as a **Google Cloud Run Job** if `BENCHMARK_TARGET=cloud-run` is specified.
4. The benchmark runner executes the target benchmark workload (e.g., `point-select`, `select-update`, `tpcc`) within the configured Google Cloud project.


---

## 3. Framework Implementation Details

Each programming language has custom packaging, build, and runtime configurations implemented to maintain consistent performance characteristics:

### Language-Specific Integration Patterns
* **Go** ([go/build_app.sh](go/build_app.sh)): Performs a sparse clone of the `google-cloud-go` repository's `spanner` directory. It integrates the local directory by writing a `replace` statement directly to `go.mod` using `go mod edit`, building the static binary, and reverting `go.mod` upon script termination.
* **Node.js** ([node/build_app.sh](node/build_app.sh)): Performs a sparse checkout of `handwritten/spanner` from `google-cloud-node`. Because simple path linking (`npm install ../path`) creates temporary symlinks that break when the build directory is cleaned up, it compiles the TypeScript client library and packages it into a physical `.tgz` archive using `npm pack`. This archive is installed in the benchmark application, ensuring the build resolves dependencies successfully.
* **Python** ([python/build_app.sh](python/build_app.sh)): Performs a sparse checkout of `packages/google-cloud-spanner` from `google-cloud-python`. It uses PyPI index overrides (`--index-url https://pypi.org/simple`) to bypass restricted network mirror constraints, and leverages Python virtual environments (`venv`) in a multi-stage Dockerfile to isolate and minimize the final container runtime size.
* **Java** ([java/build_app.sh](java/build_app.sh)): Performs a partial clone (using git filter) to clone the `google-cloud-java` repository, compiling and installing only the Spanner-related submodules to the local Maven cache before compiling the benchmark application.
* **Rust** ([rust/build_app.sh](rust/build_app.sh)): Integrates directly with the `google-cloud-rust` monorepo. It dynamically updates `Cargo.toml` dependencies to point to the unreleased branch or commit hash in the official Git repository, compiling all source dependencies inline.

### Architectural Runtime Optimizations
Because Node.js and Python execute on single-threaded event loops or under a Global Interpreter Lock (GIL), concurrency models must be adapted to the runtime characteristics. The framework implements runtime-specific scheduling adjustments to address this:
* **Node.js Hybrid Event Loop Scheduler**: Instead of busy-waiting recursively on every Event Loop tick via `setImmediate`, the Node benchmark [abstract-benchmark.ts](node/src/benchmarks/abstract-benchmark.ts) calculates the remaining nanoseconds until the next Poisson arrival. If the next task is more than `1ms` away, it yields the CPU and puts the Event Loop to sleep using `setTimeout`. Otherwise, it yields via `setImmediate` for microsecond precision. This reduces idle CPU utilization and improves callback execution queue processing.
* **Python GIL-Contention Adaptive Scheduler**: In Python's [abstract_benchmark.py](python/src/benchmarks/abstract_benchmark.py), the thread loop calculates the remaining time until the next arrival. If the arrival is more than `1ms` away, it yields the Global Interpreter Lock and lets the thread sleep via `time.sleep(remaining_sec)` instead of polling every `100us`. This reduces thread switching and GIL contention, aligning resource utilization with the Java and Go implementations.
* **Rust Async Concurrency Control**: Leverages tokio's high-performance task spawning and `tokio::sync::Semaphore` to enforce concurrency limits and backpressure natively, avoiding memory leaks and scheduler overhead under high concurrent connection loads.

### Unified Metric Aggregation & Telemetry
All languages export a standard set of OpenTelemetry metrics, sharing the exact same attributes (`benchmark_type`, `tps`, `for_alerting`, `client`, `load_type`, etc.) to allow consistent filtering and grouping in the Google Cloud Monitoring console:

1. **Latency Histogram (`spanner_client_benchmarks/latency`)**: Records transaction execution latencies in microseconds (`us`) for `point-select`, `select-update`, and `tpcc` workloads. To ensure precise percentile calculations, all languages share the same explicit bucket boundaries:
   - **50us (0.05ms) resolution** in the typical range for point queries (from 50us to 5000us, or 0.05ms to 5.0ms).
   - **1000us (1ms) resolution** between 5.0ms and 10.0ms.
   - **2000us (2ms) resolution** in the typical range for write transactions (from 10.0ms to 20.0ms).
2. **Read Latency Histogram (`spanner_client_benchmarks/read_latency`)**: Records row iteration/decoding latencies in microseconds (`us`) specifically for the `read-large-result-set` throughput benchmark.
3. **Operation Counter (`spanner_client_benchmarks/operation_count`)**: Counts the total number of benchmark operations executed (both successful and failed), serving as a baseline to verify target TPS throughput. For the closed-loop TPC-C benchmark, this metric represents the transaction execution rate, indicating the total number of transactions per second the benchmark client is able to execute.
4. **Error Counter (`spanner_client_benchmarks/error_count`)**: Counts the total number of benchmark operations that failed with an error/exception.
5. **Memory Usage (`spanner_client_benchmarks/memory_usage`)**: Measures the active resident memory usage (in bytes) of the client process.
6. **CPU Utilization (`spanner_client_benchmarks/cpu_utilization`)**: Measures the CPU utilization of the client process relative to system resources.

#### Where to Find Metrics in Google Cloud Monitoring
These metrics are exported via OpenTelemetry as custom metrics and are associated with the **Generic Task** (`generic_task`) monitored resource type:
1. Navigate to **Google Cloud Console > Monitoring > Metrics Explorer**.
2. Click on the **Select a metric** dropdown.
3. Search for or select **Generic Task** (`generic_task`) under active resources.
4. Select the **Workload** metric category, then look for the metric type names prefixed with `workload.googleapis.com/spanner_client_benchmarks/` (e.g. `spanner_client_benchmarks/latency` or `spanner_client_benchmarks/operation_count`).
5. **Grouping & Filtering**: You can filter by resource labels such as `job` (representing the configured `benchmark_name`) or group by metric attributes like `client` (representing the client library language, e.g. `java`, `go`) to compare performance across clients.

---

## 4. Benchmark Workloads

The framework currently supports the following concrete workload scenarios, which can easily be extended:

### 1. Point Query (`point-select`)
A read-only scenario that selects a single random row based on a randomly generated primary key value:
```sql
SELECT * FROM {table_name} WHERE id = @id
```
- Executed under a **single-use read-only snapshot** context.
- Measures the raw read latency of the client library and connection pools.

### 2. Select and Update (`select-update`)
A read-modify-write scenario executed inside a **Read-Write Transaction**:
1. Reads the existence of a single randomly selected row:
   ```sql
   SELECT id FROM {table_name} WHERE id = @id
   ```
2. Generates a random alphanumeric string of length between 75 and 150 characters.
3. Performs an `UPDATE` or `INSERT` DML statement based on row presence:
   ```sql
   UPDATE {table_name} SET value = @value WHERE id = @id
   ```
- Measures the locking, concurrency, and commit transaction performance of the client libraries.

### 3. Read Large Result Set (`read-large-result-set`)
Executes a query that generates a large result set with all supported data types, and iterates over the results to measure throughput and decoding/deserialization performance:
```sql
SELECT * FROM {table_name} LIMIT @num_rows
```
- Measures client-side row parsing, type decoding, and streaming iterator performance.

### 4. TPC-C Benchmark (`tpcc`)
A closed-loop TPC-C benchmark execution simulating realistic transactional application loads. It operates on a standard schema (Warehouse, District, Customer, History, Item, Stock, Orders, Order-Line, New-Orders) and executes a standardized transaction mix:
- **New-Order** (~45% of transactions): Read-write transaction adding a new order to the database.
- **Payment** (~43% of transactions): Read-write transaction processing a customer payment.
- **Order-Status** (~4% of transactions): Read-only transaction querying a customer's latest order.
- **Delivery** (~4% of transactions): Read-write transaction processing order deliveries.
- **Stock-Level** (~4% of transactions): Read-only transaction scanning stock levels.

*Scaling Factor / Data Capacity*: The scale of the benchmark is configured via the `--warehouses` option. Before running, the database must be initialized and pre-populated using the `tpcc-init` command.

*Key Telemetry & Evaluation Metrics*:
- **Throughput (`spanner_client_benchmarks/operation_count`)**: This is the most important metric to evaluate TPC-C client capabilities. Because TPC-C runs in a **closed-loop** configuration (where parallel client workers execute transactions recursively without delay/sleep periods), the rate of change of the operation count over time represents the actual transaction throughput capacity of the client library.
- **Latency (`spanner_client_benchmarks/latency`)**: Records transaction execution latencies in microseconds, labeled by transaction type (e.g. New-Order, Payment), allowing you to evaluate latency percentiles under max throughput load.

---

## Extending the Framework

The framework is designed with modular abstract classes (`Benchmark` interfaces). New benchmark scenarios (e.g., batch reads, complex multi-table transactions, or query partitions) can easily be added by implementing the abstract `Execute` method in each language and registering the subcommand in the main entry points.

---

## Regression Analysis & Metrics Comparison

The framework includes a standalone analysis application located in the [analyzer/](analyzer/) directory. The analyzer is designed to parse and evaluate performance data collected over sustained intervals to identify potential degradations.

### Analytical Capabilities
- **Historical Comparison**: Queries Google Cloud Monitoring to extract and compare metric distributions across different execution dates (e.g., comparing `P50` and `P99` latencies of the same benchmark client across two custom dates).
- **Cross-Client Evaluation**: Compares metric datasets across different client implementations (e.g., Java vs. Go) on the same day or across different days to identify language-specific performance differences.
- **Percentile Aggregation**: Calculates `P50` and `P99` query latencies aggregated over full-day windows, smoothing out transient network anomalies.
- **Baseline Tracking**: Compares current run latencies against historical baselines, such as 1-day and 7-day moving averages.
