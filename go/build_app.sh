#!/bin/bash

# Exit on error
set -e
set -x

# Store initial directory
INIT_DIR="$(pwd)"

if [ "$USE_RELEASED_VERSION" = "false" ]; then
  WORK_DIR="$(mktemp -d)"
  echo "Cloning latest Spanner Go client source into: $WORK_DIR"
  git clone --depth 1 --sparse --filter=blob:none https://github.com/googleapis/google-cloud-go.git "$WORK_DIR/spanner-repo"
  cd "$WORK_DIR/spanner-repo"
  git sparse-checkout set spanner

  # Return to initial directory where benchmark go.mod resides
  cd "$INIT_DIR"

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
