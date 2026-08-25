#!/bin/bash

# Exit on error
set -e

if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <go|java|node|python|rust>"
  exit 1
fi

CLIENT_TYPE="$1"
shift

if [ "$CLIENT_TYPE" != "go" ] && [ "$CLIENT_TYPE" != "java" ] && [ "$CLIENT_TYPE" != "node" ] && [ "$CLIENT_TYPE" != "python" ] && [ "$CLIENT_TYPE" != "rust" ]; then
  echo "Unsupported client type: $CLIENT_TYPE. Use 'go', 'java', 'node', 'python', or 'rust'."
  exit 1
fi

# Default values if not set in environment
PROJECT_ID="${PROJECT_ID:-appdev-soda-spanner-staging}"
INSTANCE_ID="${INSTANCE_ID:-knut-test-ycsb}"
BENCHMARK_TYPE="${BENCHMARK_TYPE:-point-select}"
if [ -z "$DATABASE_ID" ]; then
  if [ "$BENCHMARK_TYPE" = "tpcc" ] || [ "$BENCHMARK_TYPE" = "tpcc-init" ] || [ "$BENCHMARK_TYPE" = "ycsb" ] || [ "$BENCHMARK_TYPE" = "ycsb-init" ]; then
    DATABASE_ID="benchmarks"
  else
    DATABASE_ID="spring-data-jpa"
  fi
fi
TABLE_NAME="${TABLE_NAME:-test}"
CPU="${CPU:-4}"
MEMORY="${MEMORY:-4Gi}"
REGION="${REGION:-europe-north1}"
DURATION="${DURATION:-60m}"
FOR_ALERTING="${FOR_ALERTING:-false}"
FOR_ALERTING_FLAG=""
if [ "$FOR_ALERTING" = "true" ]; then
  FOR_ALERTING_FLAG="--for-alerting=true,"
fi
POLLING_INTERVAL="${POLLING_INTERVAL:-30}"
BENCHMARK_TARGET="${BENCHMARK_TARGET:-gce}"
MACHINE_TYPE="${MACHINE_TYPE:-}"

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

# Setup cleanup trap to remove temp files on script exit
cleanup_temp_files() {
  echo "Cleaning up temporary sidecar files in $CLIENT_TYPE..."
  rm -rf "$INIT_DIR/$CLIENT_TYPE/generator"
  rm -f "$INIT_DIR/$CLIENT_TYPE/entrypoint.sh"
  rm -f "$INIT_DIR/$CLIENT_TYPE/gce_startup.sh"
}
trap cleanup_temp_files EXIT

# Copy generator and entrypoint into client directory
echo "Copying generator and entrypoint for container build..."
cp -r ../generator ./generator
cp ../entrypoint.sh ./entrypoint.sh
if [ "$BENCHMARK_TARGET" = "gce" ]; then
  cp ../gce_startup.sh ./gce_startup.sh
fi

# Resolved image and job naming
SUFFIX="$(date +%s)-$(head -c 100 /dev/urandom | LC_ALL=C tr -dc 'a-z0-9' | head -c 4)"
IMAGE_NAME="${IMAGE_NAME:-$REGION-docker.pkg.dev/$PROJECT_ID/cloud-run-source-deploy/spanner-$CLIENT_TYPE-benchmark:$SUFFIX}"

SIDECAR_LABEL="nosidecar"
if [ "${USE_SIDECAR}" = "true" ]; then
  SIDECAR_LABEL="sidecar"
fi
JOB_NAME="${JOB_NAME:-sb-$BENCHMARK_TYPE-$CLIENT_TYPE-$SIDECAR_LABEL-$SUFFIX}"


BENCHMARK_NAME_FLAG=""
if [ -n "$BENCHMARK_NAME" ]; then
  BENCHMARK_NAME_FLAG="--benchmark-name=$BENCHMARK_NAME,"
fi

if [ "$BENCHMARK_TYPE" = "tpcc" ] || [ "$BENCHMARK_TYPE" = "tpcc-init" ]; then
  ARGS="--project=$PROJECT_ID,--instance=$INSTANCE_ID,--database=$DATABASE_ID,--duration=$DURATION,${FOR_ALERTING_FLAG}${BENCHMARK_NAME_FLAG}$BENCHMARK_TYPE,--warehouses=${WAREHOUSES:-1000},--items=${ITEMS:-1000000}"
  if [ "$BENCHMARK_TYPE" = "tpcc" ]; then
    ARGS="${ARGS},--clients=${CLIENTS:-10}"
  fi
elif [ "$BENCHMARK_TYPE" = "ycsb" ] || [ "$BENCHMARK_TYPE" = "ycsb-init" ]; then
  MOCK_FLAG=""
  if [ "$MOCK" = "true" ]; then
    MOCK_FLAG="--mock,"
  fi
  TABLE_NAME="${TABLE_NAME:-usertable}"
  ARGS="--project=$PROJECT_ID,--instance=$INSTANCE_ID,--database=$DATABASE_ID,--duration=$DURATION,${FOR_ALERTING_FLAG}${BENCHMARK_NAME_FLAG}${MOCK_FLAG}$BENCHMARK_TYPE,--table=$TABLE_NAME"
  if [ "$BENCHMARK_TYPE" = "ycsb" ]; then
    if [ -n "$WORKLOAD" ]; then ARGS="${ARGS},--workload=$WORKLOAD"; fi
    if [ -n "$DISTRIBUTION" ]; then ARGS="${ARGS},--distribution=$DISTRIBUTION"; fi
    if [ -n "$TPS" ]; then ARGS="${ARGS},--tps=$TPS"; fi
    if [ -n "$THREADS" ]; then ARGS="${ARGS},--threads=$THREADS"; fi
    if [ -n "$RECORD_COUNT" ]; then ARGS="${ARGS},--record-count=$RECORD_COUNT"; fi
    if [ -n "$ZERO_PADDING" ]; then ARGS="${ARGS},--zero-padding=$ZERO_PADDING"; fi
    if [ -n "$FIELD_COUNT" ]; then ARGS="${ARGS},--field-count=$FIELD_COUNT"; fi
    if [ -n "$FIELD_LENGTH" ]; then ARGS="${ARGS},--field-length=$FIELD_LENGTH"; fi
    if [ -n "$LOAD_TYPE" ]; then ARGS="${ARGS},--load-type=$LOAD_TYPE"; fi
    if [ "$USE_READ_ROW" = "true" ]; then ARGS="${ARGS},--use-read-row"; fi
  elif [ "$BENCHMARK_TYPE" = "ycsb-init" ]; then
    if [ -n "$RECORD_COUNT" ]; then ARGS="${ARGS},--record-count=$RECORD_COUNT"; fi
    if [ -n "$THREADS" ]; then ARGS="${ARGS},--threads=$THREADS"; fi
    if [ -n "$BATCH_SIZE" ]; then ARGS="${ARGS},--batch-size=$BATCH_SIZE"; fi
    if [ -n "$FIELD_COUNT" ]; then ARGS="${ARGS},--field-count=$FIELD_COUNT"; fi
    if [ -n "$FIELD_LENGTH" ]; then ARGS="${ARGS},--field-length=$FIELD_LENGTH"; fi
    if [ -n "$ZERO_PADDING" ]; then ARGS="${ARGS},--zero-padding=$ZERO_PADDING"; fi
    if [ "$SKIP_SCHEMA" = "true" ]; then ARGS="${ARGS},--skip-schema"; fi
    if [ "$SKIP_DATA" = "true" ]; then ARGS="${ARGS},--skip-data"; fi
  fi
else
  MOCK_FLAG=""
  if [ "$MOCK" = "true" ]; then
    MOCK_FLAG="--mock,"
    if [ -z "$LOAD_TYPE" ]; then
      LOAD_TYPE="closed-loop"
    fi
    if [ "$LOAD_TYPE" = "closed-loop" ] && [ -z "$THREADS" ]; then
      THREADS=1
    fi
  fi
  ARGS="--project=$PROJECT_ID,--instance=$INSTANCE_ID,--database=$DATABASE_ID,--duration=$DURATION,${FOR_ALERTING_FLAG}${BENCHMARK_NAME_FLAG}${MOCK_FLAG}$BENCHMARK_TYPE,--table=$TABLE_NAME"
  if [ -n "$LOAD_TYPE" ]; then ARGS="${ARGS},--load-type=$LOAD_TYPE"; fi
  if [ -n "$TPS" ]; then ARGS="${ARGS},--tps=$TPS"; fi
  if [ -n "$THREADS" ]; then ARGS="${ARGS},--threads=$THREADS"; fi
  if [ -n "$NUM_ROWS" ]; then ARGS="${ARGS},--num-rows=$NUM_ROWS"; fi
  if [ -n "$BURST_FACTOR" ]; then ARGS="${ARGS},--burst-factor=$BURST_FACTOR"; fi
  if [ -n "$BURST_DURATION" ]; then ARGS="${ARGS},--burst-duration=$BURST_DURATION"; fi
  if [ -n "$BURST_FRACTION" ]; then ARGS="${ARGS},--burst-fraction=$BURST_FRACTION"; fi
fi

ENV_FLAGS="--set-env-vars=BENCHMARK_CPU_LIMIT=$CPU"
if [ -n "$SPANNER_NUM_CHANNELS" ]; then
  ENV_FLAGS="${ENV_FLAGS},SPANNER_NUM_CHANNELS=$SPANNER_NUM_CHANNELS"
fi
if [ "$SPANNER_DISABLE_BUILTIN_METRICS" = "true" ]; then
  ENV_FLAGS="${ENV_FLAGS},SPANNER_DISABLE_BUILTIN_METRICS=true"
fi

# Add sidecar configuration env vars
ENV_FLAGS="${ENV_FLAGS},USE_SIDECAR=${USE_SIDECAR:-false}"
if [ -n "$LOAD_TYPE" ]; then ENV_FLAGS="${ENV_FLAGS},LOAD_TYPE=$LOAD_TYPE"; fi
if [ -n "$TPS" ]; then ENV_FLAGS="${ENV_FLAGS},TPS=$TPS"; fi
if [ -n "$DURATION" ]; then ENV_FLAGS="${ENV_FLAGS},DURATION=$DURATION"; fi
if [ -n "$CYCLE_DURATION" ]; then ENV_FLAGS="${ENV_FLAGS},CYCLE_DURATION=$CYCLE_DURATION"; fi
if [ -n "$PEAK_FACTOR" ]; then ENV_FLAGS="${ENV_FLAGS},PEAK_FACTOR=$PEAK_FACTOR"; fi
if [ -n "$BURST_FACTOR" ]; then ENV_FLAGS="${ENV_FLAGS},BURST_FACTOR=$BURST_FACTOR"; fi
if [ -n "$BURST_DURATION" ]; then ENV_FLAGS="${ENV_FLAGS},BURST_DURATION=$BURST_DURATION"; fi
if [ -n "$BURST_FRACTION" ]; then ENV_FLAGS="${ENV_FLAGS},BURST_FRACTION=$BURST_FRACTION"; fi

if [ "$BENCHMARK_TARGET" = "gce" ]; then
  # Determine machine type based on requested CPU if not explicitly provided
  if [ -z "$MACHINE_TYPE" ]; then
    if [ "$CPU" -le 2 ]; then
      MACHINE_TYPE="n2-standard-2"
    elif [ "$CPU" -le 4 ]; then
      MACHINE_TYPE="n2-standard-4"
    elif [ "$CPU" -le 8 ]; then
      MACHINE_TYPE="n2-standard-8"
    else
      MACHINE_TYPE="n2-standard-16"
    fi
  fi

  echo "Selected GCE machine type: $MACHINE_TYPE"

  # Translate environment variables from --set-env-vars=K=V,K2=V2 to multiple --container-env flags
  ENV_VARS="${ENV_FLAGS#*=}"
  GCE_ENV_FLAGS=""
  for var in ${ENV_VARS//,/ }; do
    GCE_ENV_FLAGS="$GCE_ENV_FLAGS --container-env=$var"
  done

  # Translate comma-separated ARGS to multiple --container-arg flags
  GCE_CONTAINER_ARGS=""
  for arg in ${ARGS//,/ }; do
    GCE_CONTAINER_ARGS="$GCE_CONTAINER_ARGS --container-arg=$arg"
  done

  VM_ZONE="${ZONE:-$REGION-a}"

  # Submit asynchronous build and GCE deployment to Cloud Build
  echo "Submitting asynchronous build and GCE deployment to Cloud Build for $CLIENT_TYPE..."
  gcloud builds submit --project "$PROJECT_ID" \
    --config ../cloudbuild_gce.yaml \
    --ignore-file=../.gcloudignore \
    --service-account="projects/$PROJECT_ID/serviceAccounts/spanner-client-benchmarks@$PROJECT_ID.iam.gserviceaccount.com" \
    --async \
    --substitutions="_IMAGE_NAME=$IMAGE_NAME,_USE_RELEASED_VERSION=${USE_RELEASED_VERSION:-false},_CLIENT_BRANCH=${CLIENT_BRANCH:-main},_CLIENT_REPO=${CLIENT_REPO:-},_JOB_NAME=$JOB_NAME,_ZONE=$VM_ZONE,_MACHINE_TYPE=$MACHINE_TYPE,_GCE_ENV_FLAGS=$GCE_ENV_FLAGS,_GCE_CONTAINER_ARGS=$GCE_CONTAINER_ARGS" \
    .
  echo "Cloud Build triggered asynchronously. GCE instance will boot on GCP once the build completes."
else
  # Build the image using Cloud Build (synchronously, like the original flow)
  echo "Building image with Cloud Build for $CLIENT_TYPE..."
  gcloud builds submit --project "$PROJECT_ID" \
    --config ../cloudbuild.yaml \
    --ignore-file=../.gcloudignore \
    --substitutions="_IMAGE_NAME=$IMAGE_NAME,_USE_RELEASED_VERSION=${USE_RELEASED_VERSION:-false},_CLIENT_BRANCH=${CLIENT_BRANCH:-main},_CLIENT_REPO=${CLIENT_REPO:-}" \
    --polling-interval="$POLLING_INTERVAL" \
    .

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
    --labels=owner=spanner-client-benchmarks \
    $ENV_FLAGS \
    --args="$ARGS"

  echo "Executing Cloud Run Job..."
  gcloud run jobs execute "$JOB_NAME" \
    --project "$PROJECT_ID" \
    --region "$REGION"
fi

cd "$INIT_DIR"

echo "Job execution started for $CLIENT_TYPE benchmark preset."
