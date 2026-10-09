# Cloud Spanner JDBC Benchmarks

This directory contains the JDBC implementation of the Cloud Spanner client benchmarks, tailored to test the performance of the Cloud Spanner JDBC driver (`google-cloud-spanner-jdbc`) against live databases.

## Scenarios
The benchmark provides the standard workload scenarios. See the top-level [README](../README.md#implemented-benchmarks) for details:
- `point-select`: Single-use read-only queries with exact staleness of 15 seconds.
- `select-update`: Read-modify-write transactional workload with internal abort retry.
- `read-large-result-set`: Large result set reading and decoding across all data types.
- `read-narrow-result-set`: Narrow result set reading and decoding.
- `tpcc`: Closed-loop TPC-C benchmark standard transaction mix.
- `tpcc-init`: Schema initialization and data ingestion for TPC-C.

---

## Features built in
1. **Builds from Source monorepo**: The benchmark builds natively against the newest local changes of the Google Cloud Java Client monorepo automatically.
2. **Continuous Alerting ready**: Exposes discrete attributes for ad-hoc manually triggered runs vs continuous daily pipeline preset runs (`client = "java-jdbc"`).
3. **Indefinite or Bounded timeouts**: Configurable to sleep infinite default mode or end in preset intervals (seconds, minutes, hours).
4. **Thread-Safe Connection Management**: Dedicated thread-local JDBC connections per worker thread with pooled underlying gRPC channels.
5. **OpenTelemetry Telemetry**: Standard latency, operations, errors, and system resource metrics reporting.

---

## Prerequisites
- **Java 17** or later (Java 25 recommended)
- **Maven 3.9+**
- Authenticated `gcloud` credentials

---

## Running locally

Launch test manually:
```bash
./run_benchmark_locally.sh jdbc -p <PROJECT> -i <INSTANCE> -d <DATABASE> point-select -t <TABLE>
```

---

## Remote deployment

To deploy via `run_benchmark.sh`:
```bash
./run_benchmark.sh jdbc
```
