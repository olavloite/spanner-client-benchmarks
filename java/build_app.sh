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
  git clone --depth 1 --branch "$CLIENT_BRANCH" --sparse --filter=blob:none https://github.com/googleapis/google-cloud-java.git "$WORK_DIR/spanner-repo" >/dev/null 2>&1
  cd "$WORK_DIR/spanner-repo"
  # Checkout all files in the repository root (e.g. license-checks.xml, pom.xml, checkstyle.xml)
  # but exclude all subdirectories except the ones we explicitly need
  git sparse-checkout set "java-spanner" "sdk-platform-java" "java-common-protos" "java-iam" >/dev/null 2>&1

  # Build Spanner artifacts and dependencies from repo root
  echo "Building Spanner artifacts..."
  cd "$INIT_DIR"
  bash ./resolve_spanner_dependencies.sh "$WORK_DIR/spanner-repo" >/dev/null 2>&1
  cd "$WORK_DIR/spanner-repo"
  mvn --batch-mode -q -pl java-spanner/google-cloud-spanner -am install -DskipTests

  # Read version
  SPANNER_VERSION=$(mvn --batch-mode -pl java-spanner/google-cloud-spanner help:evaluate -Dexpression=project.version -q -DforceStdout | tail -n 1)
  echo "Detected Spanner version: $SPANNER_VERSION"

  # Go back to initial directory
  cd "$INIT_DIR"

  # Update pom.xml with new version
  echo "Updating pom.xml with Spanner version $SPANNER_VERSION..."
  mvn --batch-mode -q versions:set-property -Dproperty=spanner.version -DnewVersion=$SPANNER_VERSION

  # Clean up work dir
  echo "Cleaning up work directory..."
  rm -rf "$WORK_DIR"
fi

# Build our app
echo "Building benchmark application..."
mvn --batch-mode -q clean package -DskipTests

echo "Build complete!"
