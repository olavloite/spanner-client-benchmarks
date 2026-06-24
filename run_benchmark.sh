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
DATABASE_ID="${DATABASE_ID:-spring-data-jpa}"
TABLE_NAME="${TABLE_NAME:-test}"
BENCHMARK_TYPE="${BENCHMARK_TYPE:-point-select}"
CPU="${CPU:-2}"
MEMORY="${MEMORY:-2Gi}"
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
  rm -rf ./generator
  rm -f ./entrypoint.sh
}
trap cleanup_temp_files EXIT

# Copy generator and entrypoint into client directory
echo "Copying generator and entrypoint for container build..."
cp -r ../generator ./generator
cp ../entrypoint.sh ./entrypoint.sh

SUFFIX="$(date +%s)-$(head -c 100 /dev/urandom | LC_ALL=C tr -dc 'a-z0-9' | head -c 4)"
IMAGE_NAME="${IMAGE_NAME:-$REGION-docker.pkg.dev/$PROJECT_ID/cloud-run-source-deploy/spanner-$CLIENT_TYPE-benchmark:$SUFFIX}"
JOB_NAME="${JOB_NAME:-sb-$BENCHMARK_TYPE-$CLIENT_TYPE-$SUFFIX}"

# Build the image using Cloud Build
echo "Building image with Cloud Build for $CLIENT_TYPE..."
gcloud builds submit --project "$PROJECT_ID" --config ../cloudbuild.yaml --substitutions="_IMAGE_NAME=$IMAGE_NAME,_USE_RELEASED_VERSION=${USE_RELEASED_VERSION:-false},_CLIENT_BRANCH=${CLIENT_BRANCH:-main},_CLIENT_REPO=${CLIENT_REPO:-}" --polling-interval="$POLLING_INTERVAL" .

cleanup_temp_files
trap - EXIT

BENCHMARK_NAME_FLAG=""
if [ -n "$BENCHMARK_NAME" ]; then
  BENCHMARK_NAME_FLAG="--benchmark-name=$BENCHMARK_NAME,"
fi

if [ "$BENCHMARK_TYPE" = "tpcc" ] || [ "$BENCHMARK_TYPE" = "tpcc-init" ]; then
  ARGS="--project=$PROJECT_ID,--instance=$INSTANCE_ID,--database=$DATABASE_ID,--duration=$DURATION,${FOR_ALERTING_FLAG}${BENCHMARK_NAME_FLAG}$BENCHMARK_TYPE,--warehouses=${WAREHOUSES:-1000},--items=${ITEMS:-1000000}"
  if [ "$BENCHMARK_TYPE" = "tpcc" ]; then
    ARGS="${ARGS},--clients=${CLIENTS:-10}"
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

ENV_FLAGS="--set-env-vars=BENCHMARK_CPU_LIMIT=$CPU,SPANNER_NUM_CHANNELS=${SPANNER_NUM_CHANNELS:-16}"
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

  # Translate environment variables from --set-env-vars=K=V,K2=V2 to --container-env=K=V,K2=V2
  ENV_VARS="${ENV_FLAGS#*=}"
  GCE_ENV_FLAGS="--container-env=$ENV_VARS"

  # Translate comma-separated ARGS to multiple --container-arg flags
  GCE_CONTAINER_ARGS=""
  for arg in ${ARGS//,/ }; do
    GCE_CONTAINER_ARGS="$GCE_CONTAINER_ARGS --container-arg=$arg"
  done

  # Construct the self-deleting startup script (runs on host VM OS)
  # It waits for the container to start, dynamically detects cores on the host,
  # pins the container to all cores except Core 0 (if multi-core), and deletes the VM on exit.
  SKIP_VM_CLEANUP="${SKIP_VM_CLEANUP:-false}"
  DELETE_VM_CMD=""
  if [ "$SKIP_VM_CLEANUP" = "false" ]; then
    DELETE_VM_CMD="NAME=\$(curl -H \"Metadata-Flavor: Google\" http://metadata.google.internal/computeMetadata/v1/instance/name)
ZONE=\$(curl -H \"Metadata-Flavor: Google\" http://metadata.google.internal/computeMetadata/v1/instance/zone | awk -F/ '{print \$NF}')
PROJECT=\$(curl -H \"Metadata-Flavor: Google\" http://metadata.google.internal/computeMetadata/v1/project/project-id)
TOKEN=\$(curl -H \"Metadata-Flavor: Google\" http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token | sed -e 's/.*\"access_token\":\"\([^\"]*\)\".*/\1/')
curl -s -X DELETE \
  -H \"Authorization: Bearer \$TOKEN\" \
  \"https://compute.googleapis.com/compute/v1/projects/\$PROJECT/zones/\$ZONE/instances/\$NAME\""
  fi

  STARTUP_SCRIPT="(
while [ -z \"\$(docker ps -q --filter name=$JOB_NAME)\" ]; do sleep 1; done
CID=\$(docker ps -q --filter name=$JOB_NAME)
NUM_CORES=\$(nproc 2>/dev/null || grep -c ^processor /proc/cpuinfo)
if [ \$NUM_CORES -gt 1 ]; then
  docker update --cpuset-cpus=1-\$((NUM_CORES - 1)) \$CID
else
  docker update --cpuset-cpus=0 \$CID
fi
docker wait \$CID
$DELETE_VM_CMD
) >/tmp/startup_script.log 2>&1 &"

  echo "Deploying dedicated GCE Spot instance VM..."
  VM_ZONE="${ZONE:-$REGION-a}"
  gcloud compute instances create-with-container "$JOB_NAME" \
    --container-image="$IMAGE_NAME" \
    --project="$PROJECT_ID" \
    --zone="$VM_ZONE" \
    --machine-type="$MACHINE_TYPE" \
    --no-address \
    --scopes="cloud-platform" \
    --service-account="spanner-client-benchmarks@$PROJECT_ID.iam.gserviceaccount.com" \
    --provisioning-model=SPOT \
    --instance-termination-action=DELETE \
    --labels=owner=spanner-client-benchmarks \
    $GCE_ENV_FLAGS \
    $GCE_CONTAINER_ARGS \
    --metadata="startup-script=$STARTUP_SCRIPT"

  echo "GCE VM instance $JOB_NAME deployed successfully and is running in the background."
else
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
