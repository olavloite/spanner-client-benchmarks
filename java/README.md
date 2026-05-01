# Cloud Spanner Java Benchmarks

This directory contains the Java implementation of the Cloud Spanner client benchmarks, tailored to test the performance of standard `google-cloud-spanner` driver scenarios against live databases.

## Scenarios
The benchmark provides two subcommands:
- **`point-select`**: Executes a single row read based on a randomly selected primary key value.
- **`select-update`**: Reads a single row and updates its payload by default transaction rate.

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

You can invoke options of the benchmark application natively:
- `-p, --project`: (Required) Target GCP Project ID.
- `-i, --instance`: (Required) Target Spanner Instance ID.
- `-d, --database`: (Required) Target Spanner Database ID.
- `--duration`: The runtime of the test (e.g. `30s`, `60m`, `1h`). Defaults to infinite indefinite. 
- `--for-alerting`: Default to continuous measurements tracking. Set true for nightly compliance runs.
- `--host`: Custom endpoint hook for local emulators.

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

