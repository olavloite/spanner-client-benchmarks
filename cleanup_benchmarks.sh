#!/bin/bash

# Exit on error
set -e

PROJECT_ID="${PROJECT_ID:-appdev-soda-spanner-staging}"
REGION="${REGION:-europe-north1}"

# Calculate expiration date (2 hours ago)
EXPIRATION_DATE=$(python3 -c "from datetime import datetime, timedelta; print((datetime.now() - timedelta(hours=2)).strftime('%Y-%m-%dT%H:%M:%SZ'))")

echo "Cleaning up benchmark artifacts older than $EXPIRATION_DATE..."

SUPPORTED_CLIENTS=("java" "go" "python" "node")

for CLIENT_TYPE in "${SUPPORTED_CLIENTS[@]}"; do
  echo "Scanning artifacts for client: $CLIENT_TYPE"
  
  # Delete old Cloud Run jobs
  JOBS_TO_DELETE=$(gcloud run jobs list --project="$PROJECT_ID" --region="$REGION" --format="value(name,metadata.creationTimestamp)" | python3 -c "
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

  for job in $JOBS_TO_DELETE; do
    if [[ $job == spanner-$CLIENT_TYPE-benchmark-job-* ]]; then
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
      echo "Deleting Artifact Registry Image: $digest"
      gcloud artifacts docker images delete "$REPO@$digest" --delete-tags --quiet || true
    done
  fi
done

echo "Cleanup complete."
