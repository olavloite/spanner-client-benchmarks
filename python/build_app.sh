#!/bin/bash

# Exit on error
set -e

USE_RELEASED_VERSION="${USE_RELEASED_VERSION:-false}"
CLIENT_BRANCH="${CLIENT_BRANCH:-main}"

# Store initial directory
INIT_DIR="$(pwd)"

if [ "$USE_RELEASED_VERSION" = "false" ]; then
  WORK_DIR="$(mktemp -d)"
  echo "Cloning Spanner Python client source into: $WORK_DIR"
  git clone --sparse --filter=blob:none https://github.com/googleapis/google-cloud-python.git "$WORK_DIR/python-repo"
  cd "$WORK_DIR/python-repo"
  echo "Checking out Spanner Python client branch/commit $CLIENT_BRANCH..."
  git checkout "$CLIENT_BRANCH"
  git sparse-checkout set packages/google-cloud-spanner

  # Return to the benchmark directory where requirements.txt resides
  cd "$INIT_DIR"

  echo "Backing up requirements.txt..."
  cp requirements.txt requirements.txt.bak

  # Setup cleanup trap
  trap 'echo "Cleaning up work directory and restoring backups..."; cd "$INIT_DIR"; [ -f requirements.txt.bak ] && mv requirements.txt.bak requirements.txt; rm -rf "$WORK_DIR"' EXIT

  # Remove google-cloud-spanner from requirements.txt to prevent double installation/conflict
  grep -v "google-cloud-spanner" requirements.txt > requirements.tmp || true

  echo "Installing local Spanner client in virtual environment..."
  pip3 install "$WORK_DIR/python-repo/packages/google-cloud-spanner" --index-url https://pypi.org/simple

  echo "Installing remaining benchmark dependencies..."
  pip3 install -r requirements.tmp --index-url https://pypi.org/simple
  rm requirements.tmp
else
  echo "Using released version of Spanner client..."
  pip3 install -r requirements.txt --index-url https://pypi.org/simple
fi

echo "Build complete!"
