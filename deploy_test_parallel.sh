#!/bin/bash

# Trigger parallel deployments of benchmarks.
# Configured for a short ad-hoc test run that does not trigger alerts.

export SKIP_CLEANUP=true
export FOR_ALERTING=false
export DURATION=30m
export USE_SIDECAR=true

CLIENTS=("java" "go" "node" "python" "rust")
TYPES=("point-select" "select-update")

echo "Starting parallel benchmark deployments..."

for client in "${CLIENTS[@]}"; do
  for type in "${TYPES[@]}"; do
    echo "Launching deployment for client=$client, benchmark=$type in background..."
    BENCHMARK_TYPE=$type ./run_benchmark.sh $client &
    # Sleep longer to stagger gcloud CLI requests and avoid ECP Proxy authentication drops
    sleep 30
  done
done

echo "All deployments launched. Waiting for VM creation to complete..."
wait
echo "All VM instances successfully deployed and running on Google Cloud!"
