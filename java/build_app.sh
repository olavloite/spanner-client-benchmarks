#!/bin/bash

# Exit on error
set -e
set -x

# Save initial directory
INIT_DIR="$(pwd)"

WORK_DIR="$(mktemp -d)"
echo "Using temporary work directory: $WORK_DIR"
echo "Cloning Spanner Java client..."
git clone --depth 1 --sparse --filter=blob:none https://github.com/googleapis/google-cloud-java.git "$WORK_DIR/spanner-repo"
cd "$WORK_DIR/spanner-repo"
# Checkout all files in the repository root (e.g. license-checks.xml, pom.xml, checkstyle.xml)
# but exclude all subdirectories except the ones we explicitly need
git sparse-checkout set "/*" "java-spanner" "sdk-platform-java" "java-common-protos" "java-iam"

# Build Spanner artifacts and dependencies from repo root
echo "Building Spanner artifacts..."
cd "$WORK_DIR/spanner-repo"
mvn -pl java-spanner/google-cloud-spanner -am install -DskipTests

# Read version
SPANNER_VERSION=$(mvn -pl java-spanner/google-cloud-spanner help:evaluate -Dexpression=project.version -q -DforceStdout | tail -n 1)
echo "Detected Spanner version: $SPANNER_VERSION"

# Go back to initial directory
cd "$INIT_DIR"

# Update pom.xml with new version
echo "Updating pom.xml with Spanner version $SPANNER_VERSION..."
mvn versions:set-property -Dproperty=spanner.version -DnewVersion=$SPANNER_VERSION

# Build our app
echo "Building benchmark application..."
mvn clean package -DskipTests

echo "Build complete!"

# Clean up work dir
echo "Cleaning up work directory..."
rm -rf "$WORK_DIR"
