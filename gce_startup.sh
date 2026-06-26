#!/bin/bash
(
  # Wait for container to start
  while [ -z "$(docker ps -q)" ]; do sleep 1; done
  CID=$(docker ps -q | head -n 1)

  # Core pinning logic
  NUM_CORES=$(nproc 2>/dev/null || grep -c ^processor /proc/cpuinfo)
  if [ $NUM_CORES -gt 1 ]; then
    docker update --cpuset-cpus=1-$((NUM_CORES - 1)) $CID
  else
    docker update --cpuset-cpus=0 $CID
  fi

  # Wait for benchmark container to finish
  docker wait $CID

  # Dynamic self-deletion via GCE metadata server
  PROJECT=$(curl -s -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/project/project-id)
  NAME=$(curl -s -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/name)
  ZONE_FULL=$(curl -s -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/zone)
  ZONE=$(basename "$ZONE_FULL")

  TOKEN=$(curl -s -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token | jq -r .access_token)

  curl -s -X DELETE \
    -H "Authorization: Bearer $TOKEN" \
    "https://compute.googleapis.com/compute/v1/projects/$PROJECT/zones/$ZONE/instances/$NAME"
) >/tmp/startup_script.log 2>&1 &
