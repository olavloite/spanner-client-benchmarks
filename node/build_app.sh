#!/bin/bash

# Exit on error
set -e
set -x

USE_RELEASED_VERSION="${USE_RELEASED_VERSION:-false}"

# Store initial directory
INIT_DIR="$(pwd)"

if [ "$USE_RELEASED_VERSION" = "false" ]; then
  WORK_DIR="$(mktemp -d)"
  echo "Cloning latest Spanner Node client source into: $WORK_DIR"
  git clone --depth 1 https://github.com/googleapis/google-cloud-node.git "$WORK_DIR/node-repo"

  echo "Building local Spanner client package..."
  cd "$WORK_DIR/node-repo/handwritten/spanner"
  npm install
  npm run compile
  TARBALL_NAME=$(npm pack | tail -n 1)
  echo "Packed Spanner client to tarball: $TARBALL_NAME"

  cd "$INIT_DIR"

  echo "Backing up package.json (and package-lock.json if present)..."
  cp package.json package.json.bak
  [ -f package-lock.json ] && cp package-lock.json package-lock.json.bak || true

  # Setup cleanup trap to restore files and delete work directory on exit
  trap 'echo "Cleaning up work directory and restoring backups..."; cd "$INIT_DIR"; [ -f package.json.bak ] && mv package.json.bak package.json; [ -f package-lock.json.bak ] && mv package-lock.json.bak package-lock.json; rm -rf "$WORK_DIR"' EXIT

  echo "Installing local Spanner client in benchmark application..."
  npm install "$WORK_DIR/node-repo/handwritten/spanner/$TARBALL_NAME" --legacy-peer-deps
else
  echo "Using released version of Spanner client..."
  npm install --legacy-peer-deps
fi

echo "Building benchmark application..."
npm run build

echo "Build complete!"
