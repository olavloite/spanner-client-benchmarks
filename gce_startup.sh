#!/bin/bash

# Define log helper function writing to console
log_info() {
  echo "$1"
  echo "gce-startup: $1" > /dev/console 2>&1
}

(
  # Get instance metadata first
  PROJECT=$(curl -s -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/project/project-id)
  NAME=$(curl -s -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/name)
  ZONE_FULL=$(curl -s -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/zone)
  ZONE=$(basename "$ZONE_FULL")

  log_info "Starting gce_startup.sh for instance: $NAME in project: $PROJECT, zone: $ZONE"

  # Wait for user container to start (prefixed with klt-INSTANCE_NAME)
  log_info "Waiting for container klt-$NAME to start..."
  while [ -z "$(docker ps --filter "name=klt-$NAME" -q)" ]; do sleep 1; done
  CID=$(docker ps --filter "name=klt-$NAME" -q | head -n 1)
  log_info "Container found: $CID"

  # Core pinning logic
  USE_SIDECAR=$(docker inspect --format='{{range .Config.Env}}{{println .}}{{end}}' $CID | grep -q '^USE_SIDECAR=true' && echo "true" || echo "false")
  NUM_CORES=$(nproc 2>/dev/null || grep -c ^processor /proc/cpuinfo)

  if [ "$USE_SIDECAR" = "true" ]; then
    log_info "Sidecar enabled. Allowing container to use all cores (0-$((NUM_CORES - 1))) so entrypoint.sh can handle isolation."
    docker update --cpuset-cpus=0-$((NUM_CORES - 1)) $CID
  elif [ $NUM_CORES -gt 1 ]; then
    log_info "Updating container cpuset to 1-$((NUM_CORES - 1))"
    docker update --cpuset-cpus=1-$((NUM_CORES - 1)) $CID
  else
    log_info "Updating container cpuset to 0"
    docker update --cpuset-cpus=0 $CID
  fi

  # Wait for benchmark container to finish
  log_info "Waiting for container $CID to finish..."
  docker logs -f $CID > /dev/console 2>&1 &
  LOGS_PID=$!
  docker wait $CID
  kill $LOGS_PID 2>/dev/null

  # Check if self-deletion is disabled for debugging
  DEBUG_VM=$(curl -s -f -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/attributes/DEBUG_VM || echo "false")
  if [ "$DEBUG_VM" = "true" ]; then
    log_info "Container $CID finished. DEBUG_VM is true, skipping self-deletion."
    exit 0
  fi

  log_info "Container $CID finished. Deleting instance..."

  # Dynamic self-deletion via GCE metadata server
  TOKEN_JSON=$(curl -s -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token)
  TOKEN=$(echo "$TOKEN_JSON" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)

  log_info "Token length: ${#TOKEN}"

  # Delete request
  log_info "Sending delete request..."
  curl -w "\nHTTP CODE: %{http_code}\n" -X DELETE \
    -H "Authorization: Bearer $TOKEN" \
    "https://compute.googleapis.com/compute/v1/projects/$PROJECT/zones/$ZONE/instances/$NAME" > /tmp/delete_out.txt 2>&1
  
  curl_status=$?
  log_info "Curl exit status: $curl_status"
  log_info "Curl output:"
  while read -r line; do log_info "  $line"; done < /tmp/delete_out.txt
) >/tmp/startup_script.log 2>&1 &
