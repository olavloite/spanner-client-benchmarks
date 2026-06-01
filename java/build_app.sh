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
  echo "Cloning Spanner Java client repository..."
  git clone --filter=blob:none https://github.com/googleapis/google-cloud-java.git "$WORK_DIR/spanner-repo"
  cd "$WORK_DIR/spanner-repo"
  echo "Checking out Spanner Java client branch/commit $CLIENT_BRANCH..."
  git checkout "$CLIENT_BRANCH"

  # Build Spanner artifacts and dependencies from repo root
  echo "Building Spanner artifacts..."
  mvn --batch-mode -pl grpc-gcp-java,java-spanner/google-cloud-spanner -am install -DskipTests

  # Read versions
  SPANNER_VERSION=$(python3 -c "import xml.etree.ElementTree as ET; root = ET.parse('java-spanner/google-cloud-spanner/pom.xml').getroot(); ns = {'m': 'http://maven.apache.org/POM/4.0.0'}; v = root.find('m:version', ns); print(v.text if v is not None else root.find('m:parent/m:version', ns).text)")
  GRPC_GCP_VERSION=$(python3 -c "import xml.etree.ElementTree as ET; root = ET.parse('grpc-gcp-java/pom.xml').getroot(); ns = {'m': 'http://maven.apache.org/POM/4.0.0'}; v = root.find('m:version', ns); print(v.text if v is not None else root.find('m:parent/m:version', ns).text)")
  echo "Detected Spanner version: $SPANNER_VERSION"
  echo "Detected grpc-gcp version: $GRPC_GCP_VERSION"

  # Go back to initial directory
  cd "$INIT_DIR"

  # Update pom.xml with new versions
  sed -i.bak "s|<spanner.version>.*</spanner.version>|<spanner.version>$SPANNER_VERSION</spanner.version>|g" pom.xml
  sed -i.bak "s|<grpc-gcp.version>.*</grpc-gcp.version>|<grpc-gcp.version>$GRPC_GCP_VERSION</grpc-gcp.version>|g" pom.xml
  rm -f pom.xml.bak

  # Clean up work dir
  echo "Cleaning up work directory..."
  rm -rf "$WORK_DIR"
fi

# Build our app
echo "Building benchmark application..."
mvn --batch-mode clean package -DskipTests

echo "Build complete!"
