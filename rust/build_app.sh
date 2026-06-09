#!/bin/bash

# Exit on error
set -e

USE_RELEASED_VERSION="${USE_RELEASED_VERSION:-false}"
CLIENT_BRANCH="${CLIENT_BRANCH:-main}"
CLIENT_REPO="${CLIENT_REPO:-https://github.com/googleapis/google-cloud-rust}"

if [ "$USE_RELEASED_VERSION" = "false" ]; then
  # 1. Update repository URL
  if [ "$CLIENT_REPO" != "https://github.com/googleapis/google-cloud-rust" ]; then
    echo "Updating Cargo.toml to use repository $CLIENT_REPO..."
    sed -i.bak "s|git = \"https://github.com/googleapis/google-cloud-rust\"|git = \"$CLIENT_REPO\"|g" Cargo.toml
  fi

  # 2. Update branch/commit if not main
  if [ "$CLIENT_BRANCH" != "main" ]; then
    if [[ "$CLIENT_BRANCH" =~ ^[0-9a-f]{7,40}$ ]]; then
      echo "Updating Cargo.toml to use commit hash $CLIENT_BRANCH..."
      sed -i.bak "s|git = \"$CLIENT_REPO\", package = \"google-cloud-spanner\"|git = \"$CLIENT_REPO\", rev = \"$CLIENT_BRANCH\", package = \"google-cloud-spanner\"|g" Cargo.toml
      sed -i.bak "s|git = \"$CLIENT_REPO\", package = \"spanner-grpc-mock\"|git = \"$CLIENT_REPO\", rev = \"$CLIENT_BRANCH\", package = \"spanner-grpc-mock\"|g" Cargo.toml
    else
      echo "Updating Cargo.toml to use branch $CLIENT_BRANCH..."
      sed -i.bak "s|git = \"$CLIENT_REPO\", package = \"google-cloud-spanner\"|git = \"$CLIENT_REPO\", branch = \"$CLIENT_BRANCH\", package = \"google-cloud-spanner\"|g" Cargo.toml
      sed -i.bak "s|git = \"$CLIENT_REPO\", package = \"spanner-grpc-mock\"|git = \"$CLIENT_REPO\", branch = \"$CLIENT_BRANCH\", package = \"spanner-grpc-mock\"|g" Cargo.toml
    fi
  fi
  rm -f Cargo.toml.bak
fi

echo "Building benchmark application in release mode..."
cargo build --release

echo "Build complete!"
