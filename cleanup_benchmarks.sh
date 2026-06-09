#!/bin/bash

# Exit on error
set -e

PROJECT_ID="${PROJECT_ID:-appdev-soda-spanner-staging}"
REGION="${REGION:-europe-north1}"

# Calculate expiration date (12 hours ago)
EXPIRATION_DATE=$(python3 -c "from datetime import datetime, timedelta, timezone; print((datetime.now(timezone.utc) - timedelta(hours=12)).strftime('%Y-%m-%dT%H:%M:%SZ'))")

echo "Cleaning up benchmark artifacts older than $EXPIRATION_DATE..."

SUPPORTED_CLIENTS=("java" "go" "python" "node" "rust")

for CLIENT_TYPE in "${SUPPORTED_CLIENTS[@]}"; do
  echo "Scanning artifacts for client: $CLIENT_TYPE"
  
  # Delete old Cloud Run jobs
  JOBS_TO_DELETE=$(gcloud run jobs list --project="$PROJECT_ID" --region="$REGION" --filter="labels.owner=spanner-client-benchmarks OR name:spanner-benchmark- OR name:spanner-$CLIENT_TYPE-benchmark-job-" --format="value(name,metadata.creationTimestamp)" | python3 -c "
import sys
from datetime import datetime, timezone
threshold = datetime.fromisoformat('$EXPIRATION_DATE'.replace('Z', '+00:00'))
for line in sys.stdin:
    parts = line.strip().split()
    if len(parts) == 2:
        name, create_time_str = parts
        create_time = datetime.fromisoformat(create_time_str.replace('Z', '+00:00'))
        if create_time < threshold:
            print(name)
")

  RUNNING_DIGESTS=""

  # Track and protect images used by active GCE VM instances
  GCE_IMAGES=\$(gcloud compute instances list \
    --filter="labels.owner=spanner-client-benchmarks" \
    --project="$PROJECT_ID" \
    --format="value(metadata.items.gce-container-declaration)" 2>/dev/null | \
    grep -oE "$REGION-docker.pkg.dev/$PROJECT_ID/cloud-run-source-deploy/spanner-$CLIENT_TYPE-benchmark:[a-zA-Z0-9.-]+")

  for image in \$GCE_IMAGES; do
    DIGEST=\$(gcloud artifacts docker images describe "\$image" --project="$PROJECT_ID" --format="value(image_summary.digest)" 2>/dev/null || true)
    if [ -n "\$DIGEST" ]; then
      RUNNING_DIGESTS="\$RUNNING_DIGESTS \$DIGEST"
    fi
  done

  for job in $JOBS_TO_DELETE; do
    if [[ $job == spanner-$CLIENT_TYPE-benchmark-job-* ]] || [[ $job =~ ^(spanner-benchmark|sb)-.*-$CLIENT_TYPE-[0-9]+-[a-z0-9]+$ ]]; then
      # Check if job has active executions
      ACTIVE_EXECS=$(gcloud run jobs executions list --project="$PROJECT_ID" --region="$REGION" --job="$job" --format="value(metadata.name,status.completionTime)" | python3 -c "
import sys
for line in sys.stdin:
    parts = line.strip().split()
    if len(parts) == 1: # Only name is present, completionTime is null/missing
        print(parts[0])
")
      if [ -n "$ACTIVE_EXECS" ]; then
        echo "Skipping deletion of running job: $job"
        # Collect the image used by this job
        IMAGE=$(gcloud run jobs describe "$job" --project="$PROJECT_ID" --region="$REGION" --format="value(spec.template.spec.containers[0].image)")
        DIGEST=$(gcloud artifacts docker images describe "$IMAGE" --format="value(image_summary.digest)" 2>/dev/null || true)
        RUNNING_DIGESTS="$RUNNING_DIGESTS $DIGEST"
        continue
      fi
      
      echo "Deleting Cloud Run Job: $job"
      gcloud run jobs delete "$job" --project="$PROJECT_ID" --region="$REGION" --quiet
    fi
  done

  # Delete old Artifact Registry images
  REPO="$REGION-docker.pkg.dev/$PROJECT_ID/cloud-run-source-deploy/spanner-$CLIENT_TYPE-benchmark"
  if gcloud artifacts docker images list "$REPO" &>/dev/null; then
    IMAGES_TO_DELETE=$(gcloud artifacts docker images list "$REPO" --format="value(version,createTime)" | python3 -c "
import sys
from datetime import datetime, timezone
threshold = datetime.fromisoformat('$EXPIRATION_DATE'.replace('Z', '+00:00'))
for line in sys.stdin:
    parts = line.strip().split()
    if len(parts) == 2:
        digest, create_time_str = parts
        # format: 2026-04-30T16:30:58
        create_time = datetime.fromisoformat(create_time_str).replace(tzinfo=timezone.utc)
        if create_time < threshold:
            print(digest)
")

    for digest in $IMAGES_TO_DELETE; do
      if [[ $RUNNING_DIGESTS == *"$digest"* ]]; then
        echo "Skipping deletion of image used by running job: $digest"
        continue
      fi
      echo "Deleting Artifact Registry Image: $digest"
      gcloud artifacts docker images delete "$REPO@$digest" --delete-tags --quiet || true
    done
  fi
done

# Clean up leaked/orphaned GCE VM instances older than 12 hours
echo "Checking for leaked GCE VM instances older than $EXPIRATION_DATE..."
LEAKED_VMS=$(gcloud compute instances list \
  --project="$PROJECT_ID" \
  --filter="labels.owner=spanner-client-benchmarks" \
  --format="value(name,zone,creationTimestamp)" | python3 -c "
import sys
from datetime import datetime, timezone
threshold = datetime.fromisoformat('$EXPIRATION_DATE'.replace('Z', '+00:00'))
for line in sys.stdin:
    parts = line.strip().split()
    if len(parts) == 3:
        name, zone_path, create_time_str = parts
        zone = zone_path.split('/')[-1]
        create_time = datetime.fromisoformat(create_time_str.replace('Z', '+00:00'))
        if create_time < threshold:
            print(f'{name} {zone}')
")

for row in $LEAKED_VMS; do
  read -r name zone <<< "$row"
  echo "Deleting leaked GCE VM instance: $name in zone $zone"
  gcloud compute instances delete "$name" --project="$PROJECT_ID" --zone="$zone" --quiet --async
done

echo "Cleanup complete."
