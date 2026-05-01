#!/bin/bash

# Exit on error
set -e
set -x

USE_RELEASED_VERSION="${USE_RELEASED_VERSION:-false}"

if [ "$USE_RELEASED_VERSION" = "false" ]; then
  WORK_DIR="$(mktemp -d)"
  echo "Cloning latest Spanner Go client source into: $WORK_DIR"
  git clone --depth 1 https://github.com/googleapis/google-cloud-go.git "$WORK_DIR/spanner-repo"

  echo "Integrating local Spanner reference to go.mod..."
  go mod edit -replace=cloud.google.com/go/spanner="$WORK_DIR/spanner-repo/spanner"
  echo "Resolving dynamic dependencies..."
  go mod tidy
  
  # Ensure we drop the replace statement on exit or error
  trap 'echo "Reverting go.mod adjustments..."; go mod edit -dropreplace=cloud.google.com/go/spanner; rm -rf "$WORK_DIR"' EXIT
fi

echo "Building benchmark application..."
go build -o benchmark-app .

echo "Build complete!"
