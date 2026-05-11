#!/bin/bash

# Exit on error
set -e

USE_RELEASED_VERSION="${USE_RELEASED_VERSION:-false}"

# --- ADD THESE LINES TO SUPPORT CUSTOM BRANCHES ---
GOOGLE_CLOUD_NODE_REPO="${GOOGLE_CLOUD_NODE_REPO:-https://github.com/googleapis/google-cloud-node.git}"
GOOGLE_CLOUD_NODE_BRANCH="${GOOGLE_CLOUD_NODE_BRANCH:-configurable-affinity-keys}"
GRPC_GCP_NODE_REPO="${GRPC_GCP_NODE_REPO:-https://github.com/alkatrivedi/grpc-gcp-node.git#feat/metadata-affinity-control}" # e.g., github:your-username/grpc-gcp-node#your-branch

# Store initial directory
INIT_DIR="$(pwd)"

if [ "$USE_RELEASED_VERSION" = "false" ]; then
  WORK_DIR="$(mktemp -d)"
  echo "Cloning Spanner Node client source from $GOOGLE_CLOUD_NODE_REPO branch $GOOGLE_CLOUD_NODE_BRANCH into: $WORK_DIR"
  
  # MODIFIED: Added --branch and using variable for repo URL
  git clone --depth 1 --branch "$GOOGLE_CLOUD_NODE_BRANCH" --sparse --filter=blob:none "$GOOGLE_CLOUD_NODE_REPO" "$WORK_DIR/node-repo"
  
  cd "$WORK_DIR/node-repo"
  echo "Checking out Spanner Node client branch/commit $CLIENT_BRANCH..."
  git checkout "$CLIENT_BRANCH"
  git sparse-checkout set handwritten/spanner
  echo "Building local Spanner client package..."
  cd "$WORK_DIR/node-repo/handwritten/spanner"
  
  # --- ADD THESE LINES TO INSTALL CUSTOM GRPC-GCP ---
  if [ -n "$GRPC_GCP_NODE_REPO" ]; then
    echo "Installing custom grpc-gcp from $GRPC_GCP_NODE_REPO..."
    npm install "$GRPC_GCP_NODE_REPO" --legacy-peer-deps
  fi
  # --------------------------------------------------
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
