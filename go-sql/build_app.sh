#!/bin/bash

# Exit on error
set -e

# Store initial directory
INIT_DIR="$(pwd)"

USE_RELEASED_VERSION="${USE_RELEASED_VERSION:-false}"
CLIENT_BRANCH="${CLIENT_BRANCH:-main}"
CLIENT_REPO="${CLIENT_REPO:-https://github.com/googleapis/go-sql-spanner.git}"

if [ "$USE_RELEASED_VERSION" = "false" ]; then
  WORK_DIR="$(mktemp -d)"
  echo "Cloning Spanner Go database/sql driver source from $CLIENT_REPO into: $WORK_DIR"
  git clone --filter=blob:none "$CLIENT_REPO" "$WORK_DIR/spanner-repo"
  cd "$WORK_DIR/spanner-repo"
  echo "Checking out Spanner Go database/sql driver branch/commit $CLIENT_BRANCH..."
  git checkout "$CLIENT_BRANCH"

  # Return to initial directory where benchmark go.mod resides
  cd "$INIT_DIR"

  echo "Integrating local Spanner driver reference to go.mod..."
  go mod edit -replace=github.com/googleapis/go-sql-spanner="$WORK_DIR/spanner-repo"
  echo "Resolving dynamic dependencies..."
  go mod tidy

  # Ensure we drop the replace statement on exit or error
  trap 'echo "Reverting go.mod adjustments..."; go mod edit -dropreplace=github.com/googleapis/go-sql-spanner; rm -rf "$WORK_DIR"' EXIT
fi

echo "Building benchmark application..."
go build -o benchmark-app .

echo "Build complete!"
