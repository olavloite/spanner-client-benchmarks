# Cloud Spanner Java Benchmarks

This directory contains the Java implementation of the Cloud Spanner client benchmarks, tailored to test the performance of standard `google-cloud-spanner` driver scenarios against live databases.

## Scenarios
The benchmark provides the standard workload scenarios. See the top-level [README](../README.md#implemented-benchmarks) for details.

---

## Features built in
1. **Builds from Source mono repository**: The benchmark builds natively against the newest local changes of the Google Cloud Java Client monorepo automatically. 
2. **Continuous Alerting ready**: Exposes discrete attributes for ad-hoc manually triggered runs vs continuous daily pipeline preset runs. 
3. **Indefinite or Bounded timeouts setup**: Configurable to sleep infinite default mode or end in preset intervals (seconds, minutes, hours).
4. **Automated maintenance**: Periodic automated cleanup routines to prevent leftover costs for automated jobs and container registry images. 

---

## Prerequisites
- **Java 17** or later
- **Maven**
- Authenticated `gcloud` credentials

---

## Configuration Options

The benchmark supports all standard options described in the top-level [README](../README.md#configuration-options).

Supported arguments here:
- `-p, --project`, `-i, --instance`, `-d, --database`: (Required) Connection details.
- `-t, --table`: (Required) Target database table name.
- `--tps`, `--threads`, `--num-rows`: Execution parameters.
- `--burst-factor`, `--burst-duration`, `--burst-fraction`: Bursty load configuration.

---

## Running locally

Launch test manually without building in isolated container default context:
```bash
./run_benchmark_locally.sh -p <PROJ> -i <INST> -d <DB> point-select -t <TABLE>
```

---

## Remote deployment (Cloud Run preset)

To build and deploy in continuous preset mode:
```bash
# Defaults to for_alerting=true DURATION=60m
./run_benchmark.sh 
```

You can opt-out of scheduled maintain and cleanups by running:
```bash
SKIP_CLEANUP=true ./run_benchmark.sh
```

