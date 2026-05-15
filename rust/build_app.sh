#!/bin/bash

# Exit on error
set -e

USE_RELEASED_VERSION="${USE_RELEASED_VERSION:-false}"
CLIENT_BRANCH="${CLIENT_BRANCH:-main}"

if [ "$USE_RELEASED_VERSION" = "false" ]; then
  if [ "$CLIENT_BRANCH" != "main" ]; then
    echo "Updating Cargo.toml to use branch $CLIENT_BRANCH..."
    sed -i.bak "s|git = \"https://github.com/googleapis/google-cloud-rust\", package = \"google-cloud-spanner\"|git = \"https://github.com/googleapis/google-cloud-rust\", branch = \"$CLIENT_BRANCH\", package = \"google-cloud-spanner\"|g" Cargo.toml
  fi
fi

echo "Building benchmark application in release mode..."
cargo build --release

echo "Build complete!"
