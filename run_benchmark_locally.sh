#!/bin/bash

# Exit on error
set -e

if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <go|java> [options] <benchmark-type>"
  exit 1
fi

CLIENT_TYPE="$1"
shift

INIT_DIR="$(pwd)"

if [ "$CLIENT_TYPE" != "go" ] && [ "$CLIENT_TYPE" != "java" ]; then
  echo "Unsupported client type: $CLIENT_TYPE. Use 'go' or 'java'."
  exit 1
fi

echo "Building benchmark application for $CLIENT_TYPE..."
cd "$CLIENT_TYPE"
./build_app.sh

echo "Running benchmark locally..."
if [ "$CLIENT_TYPE" = "java" ]; then
  java -jar target/spanner-java-benchmark-1.0-SNAPSHOT.jar "$@"
elif [ "$CLIENT_TYPE" = "go" ]; then
  ./benchmark-app "$@"
fi

cd "$INIT_DIR"
