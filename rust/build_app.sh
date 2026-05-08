#!/bin/bash

# Exit on error
set -e
set -x

echo "Building benchmark application in release mode..."
cargo build --release

echo "Build complete!"
