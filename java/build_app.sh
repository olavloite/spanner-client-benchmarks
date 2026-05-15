#!/bin/bash

# Exit on error
set -e

# Save initial directory
INIT_DIR="$(pwd)"

USE_RELEASED_VERSION="${USE_RELEASED_VERSION:-false}"
CLIENT_BRANCH="${CLIENT_BRANCH:-main}"

if [ "$USE_RELEASED_VERSION" = "false" ]; then
  WORK_DIR="$(mktemp -d)"
  echo "Using temporary work directory: $WORK_DIR"
  echo "Cloning Spanner Java client branch $CLIENT_BRANCH..."
  git clone --depth 1 --branch "$CLIENT_BRANCH" --filter=blob:none https://github.com/googleapis/google-cloud-java.git "$WORK_DIR/spanner-repo"
  cd "$WORK_DIR/spanner-repo"

  # Build Spanner artifacts and dependencies from repo root
  echo "Building Spanner artifacts..."
  mvn --batch-mode -pl java-spanner/google-cloud-spanner -am install -DskipTests

  # Read version
  SPANNER_VERSION=$(grep "<version>" java-spanner/google-cloud-spanner/pom.xml | head -n 1 | sed "s/.*<version>\(.*\)<\/version>.*/\1/")
  echo "Detected Spanner version: $SPANNER_VERSION"

  # Go back to initial directory
  cd "$INIT_DIR"

  # Update pom.xml with new version
  sed -i.bak "s|<spanner.version>.*</spanner.version>|<spanner.version>$SPANNER_VERSION</spanner.version>|g" pom.xml
  rm -f pom.xml.bak

  # Clean up work dir
  echo "Cleaning up work directory..."
  rm -rf "$WORK_DIR"
fi

# Build our app
echo "Building benchmark application..."
mvn --batch-mode clean package -DskipTests

echo "Build complete!"
