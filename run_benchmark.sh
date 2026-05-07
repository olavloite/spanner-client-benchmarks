#!/bin/bash

# Exit on error
set -e

if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <go|java|node|python>"
  exit 1
fi

CLIENT_TYPE="$1"
shift

if [ "$CLIENT_TYPE" != "go" ] && [ "$CLIENT_TYPE" != "java" ] && [ "$CLIENT_TYPE" != "node" ] && [ "$CLIENT_TYPE" != "python" ]; then
  echo "Unsupported client type: $CLIENT_TYPE. Use 'go', 'java', 'node', or 'python'."
  exit 1
fi

# Default values if not set in environment
PROJECT_ID="${PROJECT_ID:-appdev-soda-spanner-staging}"
INSTANCE_ID="${INSTANCE_ID:-knut-test-ycsb}"
DATABASE_ID="${DATABASE_ID:-spring-data-jpa}"
TABLE_NAME="${TABLE_NAME:-test}"
BENCHMARK_TYPE="${BENCHMARK_TYPE:-point-select}"
CPU="${CPU:-8}"
MEMORY="${MEMORY:-32Gi}"
REGION="${REGION:-europe-north1}"
DURATION="${DURATION:-60m}"
FOR_ALERTING="${FOR_ALERTING:-false}"
FOR_ALERTING_FLAG=""
if [ "$FOR_ALERTING" = "true" ]; then
  FOR_ALERTING_FLAG="--for-alerting=true,"
fi
POLLING_INTERVAL="${POLLING_INTERVAL:-30}"

if [[ $DURATION == *h ]]; then
  DURATION_SECONDS=$((${DURATION%h} * 3600))
elif [[ $DURATION == *m ]]; then
  DURATION_SECONDS=$((${DURATION%m} * 60))
elif [[ $DURATION == *s ]]; then
  DURATION_SECONDS=${DURATION%s}
else
  DURATION_SECONDS=$DURATION
fi
TASK_TIMEOUT=$((DURATION_SECONDS + 300))s

INIT_DIR="$(pwd)"

SKIP_CLEANUP="${SKIP_CLEANUP:-false}"
if [ "$SKIP_CLEANUP" = "false" ]; then
  echo "Running automated cleanup of old artifacts..."
  ./cleanup_benchmarks.sh
fi

cd "$CLIENT_TYPE"

SUFFIX="$(date +%s)-$(head -c 100 /dev/urandom | LC_ALL=C tr -dc 'a-z0-9' | head -c 4)"
IMAGE_NAME="${IMAGE_NAME:-$REGION-docker.pkg.dev/$PROJECT_ID/cloud-run-source-deploy/spanner-$CLIENT_TYPE-benchmark:$SUFFIX}"
JOB_NAME="${JOB_NAME:-spanner-$CLIENT_TYPE-benchmark-job-$SUFFIX}"

# Build the image using Cloud Build
echo "Building image with Cloud Build for $CLIENT_TYPE..."
gcloud builds submit --project "$PROJECT_ID" --tag "$IMAGE_NAME" --polling-interval="$POLLING_INTERVAL" .

ARGS="--project=$PROJECT_ID,--instance=$INSTANCE_ID,--database=$DATABASE_ID,--duration=$DURATION,${FOR_ALERTING_FLAG}$BENCHMARK_TYPE,--table=$TABLE_NAME"
if [ -n "$TPS" ]; then ARGS="${ARGS},--tps=$TPS"; fi
if [ -n "$THREADS" ]; then ARGS="${ARGS},--threads=$THREADS"; fi
if [ -n "$NUM_ROWS" ]; then ARGS="${ARGS},--num-rows=$NUM_ROWS"; fi

# Create or update the Cloud Run Job
echo "Deploying Cloud Run Job..."
gcloud run jobs deploy "$JOB_NAME" \
  --image "$IMAGE_NAME" \
  --project "$PROJECT_ID" \
  --region "$REGION" \
  --cpu "$CPU" \
  --memory "$MEMORY" \
  --task-timeout "$TASK_TIMEOUT" \
  --max-retries 0 \
  --args="$ARGS"

echo "Executing Cloud Run Job..."
gcloud run jobs execute "$JOB_NAME" \
  --project "$PROJECT_ID" \
  --region "$REGION"

cd "$INIT_DIR"

echo "Job execution started for $CLIENT_TYPE benchmark preset."
