#!/bin/bash
(
  # Wait for container to start
  while [ -z "$(docker ps -q)" ]; do sleep 1; done
  CID=$(docker ps -q | head -n 1)

  # Core pinning logic
  USE_SIDECAR=$(docker inspect --format='{{range .Config.Env}}{{println .}}{{end}}' $CID | grep -q '^USE_SIDECAR=true' && echo "true" || echo "false")
  NUM_CORES=$(nproc 2>/dev/null || grep -c ^processor /proc/cpuinfo)

  if [ "$USE_SIDECAR" = "true" ]; then
    echo "Sidecar enabled. Allowing container to use all cores (0-$((NUM_CORES - 1))) so entrypoint.sh can handle isolation."
    docker update --cpuset-cpus=0-$((NUM_CORES - 1)) $CID
  elif [ $NUM_CORES -gt 1 ]; then
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

  TOKEN_JSON=$(curl -s -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token)
  TOKEN=$(echo "$TOKEN_JSON" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)

  curl -s -X DELETE \
    -H "Authorization: Bearer $TOKEN" \
    "https://compute.googleapis.com/compute/v1/projects/$PROJECT/zones/$ZONE/instances/$NAME"
) >/tmp/startup_script.log 2>&1 &
