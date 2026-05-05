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
2. Performs a **shallow, sparse checkout** of the `google-cloud-java` repository, downloading only the Spanner module (`java-spanner`) and its immediate snapshot modules (`sdk-platform-java`, `java-common-protos`, and `java-iam`), excluding unused modules.
3. Compiles and installs these snapshot modules into the local Maven cache (`~/.m2/repository`).
4. Evaluates the current repository version of Spanner and updates the benchmark's `pom.xml` to compile against it.
5. Compiles the benchmark application, producing the benchmark application JAR.

### Containerization
The benchmark's [java/Dockerfile](java/Dockerfile) leverages a **multi-stage build**:
- **Build Stage**: Pulls a full Maven image, runs the `build_app.sh` script, downloads the latest Spanner source code, compiles dependencies, and builds the final JAR inside the container.
- **Runtime Stage**: Pulls a lightweight JRE image, copies only the final compiled JAR and libraries from the build stage, and sets the entry point.

### Job Deployment and Execution
When executing `./run_benchmark.sh java`:
1. The workspace context is uploaded to **Google Cloud Build** via `gcloud builds submit`.
2. Cloud Build compiles the source code and builds the Docker image inside Artifact Registry.
3. The runner deploys the image as a **Google Cloud Run Job**, overriding vCPU, Memory, and runtime environment parameters.
4. The runner triggers a job execution, executing the Point-Select or Select-Update workload natively in Google Cloud staging.

---

## 3. Framework Implementation Details

Each programming language has custom packaging, build, and runtime configurations implemented to maintain consistent performance characteristics:

### Language-Specific Integration Patterns
* **Go** ([go/build_app.sh](go/build_app.sh)): Performs a sparse clone of the `google-cloud-go` repository's `spanner` directory. It integrates the local directory by writing a `replace` statement directly to `go.mod` using `go mod edit`, building the static binary, and reverting `go.mod` upon script termination.
* **Node.js** ([node/build_app.sh](node/build_app.sh)): Performs a sparse checkout of `handwritten/spanner` from `google-cloud-node`. Because simple path linking (`npm install ../path`) creates temporary symlinks that break when the build directory is cleaned up, it compiles the TypeScript client library and packages it into a physical `.tgz` archive using `npm pack`. This archive is installed in the benchmark application, ensuring the build resolves dependencies successfully.
* **Python** ([python/build_app.sh](python/build_app.sh)): Performs a sparse checkout of `packages/google-cloud-spanner` from `google-cloud-python`. It uses PyPI index overrides (`--index-url https://pypi.org/simple`) to bypass restricted network mirror constraints, and leverages Python virtual environments (`venv`) in a multi-stage Dockerfile to isolate and minimize the final container runtime size.
* **Java** ([java/build_app.sh](java/build_app.sh)): Utilizes Git sparse checkout to clone only `java-spanner` and its sibling snapshot libraries (`sdk-platform-java`, `java-common-protos`, `java-iam`), compiling and caching them locally before compiling the benchmark app.

### Architectural Runtime Optimizations
Because Node.js and Python execute on single-threaded event loops or under a Global Interpreter Lock (GIL), concurrency models must be adapted to the runtime characteristics. The framework implements runtime-specific scheduling adjustments to address this:
* **Node.js Hybrid Event Loop Scheduler**: Instead of busy-waiting recursively on every Event Loop tick via `setImmediate`, the Node benchmark [abstract-benchmark.ts](node/src/benchmarks/abstract-benchmark.ts) calculates the remaining nanoseconds until the next Poisson arrival. If the next task is more than `1ms` away, it yields the CPU and puts the Event Loop to sleep using `setTimeout`. Otherwise, it yields via `setImmediate` for microsecond precision. This reduces idle CPU utilization and improves callback execution queue processing.
* **Python GIL-Contention Adaptive Scheduler**: In Python's [abstract_benchmark.py](python/src/benchmarks/abstract_benchmark.py), the thread loop calculates the remaining time until the next arrival. If the arrival is more than `1ms` away, it yields the Global Interpreter Lock and lets the thread sleep via `time.sleep(remaining_sec)` instead of polling every `100us`. This reduces thread switching and GIL contention, aligning resource utilization with the Java and Go implementations.

### Unified Metric Aggregation & Telemetry
All languages export a standard set of OpenTelemetry metrics, sharing the exact same attributes (`benchmark_type`, `tps`, `for_alerting`, `client`) to allow consistent filtering and grouping in the Google Cloud Monitoring console:

1. **Latency Histogram (`spanner_client_benchmarks/latency`)**: Records transaction execution latencies in microseconds (`us`). To ensure precise percentile calculations, all languages share the same explicit bucket boundaries:
   - **0.5ms resolution** in the typical range for point queries (from 0.5ms to 5.0ms).
   - **1ms resolution** between 5.0ms and 10.0ms.
   - **2ms resolution** in the typical range for write transactions (from 10.0ms to 20.0ms).
2. **Operation Counter (`spanner_client_benchmarks/operation_count`)**: Counts the total number of benchmark operations executed (both successful and failed), serving as a baseline to verify target TPS throughput.
3. **Error Counter (`spanner_client_benchmarks/error_count`)**: Counts the total number of benchmark operations that failed with an error/exception.

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
