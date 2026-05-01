#!/bin/bash

# Exit on error
set -e

REPO_DIR="$1"

if [ -z "$REPO_DIR" ]; then
    echo "Usage: $0 <PATH_TO_SPANNER_REPO>"
    exit 1
fi

echo "Resolving Spanner dependencies in $REPO_DIR..."

# Run the commands with paths relative to REPO_DIR or using -f with absolute paths!
# We use -f to specify the POM file for specific modules.

mvn -f "$REPO_DIR/sdk-platform-java/pom.xml" -pl gapic-generator-java-bom,java-core/google-cloud-core-bom -am clean install -DskipTests -Dmaven.test.skip=true -Dcheckstyle.skip=true

mvn -f "$REPO_DIR/sdk-platform-java/java-shared-dependencies/third-party-dependencies/pom.xml" clean install -DskipTests -Dmaven.test.skip=true -Dcheckstyle.skip=true

mvn -f "$REPO_DIR/sdk-platform-java/api-common-java/pom.xml" clean install -DskipTests -Dmaven.test.skip=true -Dcheckstyle.skip=true

mvn -f "$REPO_DIR/java-common-protos/pom.xml" clean install -DskipTests -Dmaven.test.skip=true -Dcheckstyle.skip=true

mvn -f "$REPO_DIR/sdk-platform-java/gax-java/pom.xml" clean install -DskipTests -Dmaven.test.skip=true -Dcheckstyle.skip=true

mvn -f "$REPO_DIR/sdk-platform-java/java-shared-dependencies/first-party-dependencies/pom.xml" clean install -DskipTests -Dmaven.test.skip=true -Dcheckstyle.skip=true

mvn -f "$REPO_DIR/sdk-platform-java/java-shared-dependencies/pom.xml" clean install -DskipTests -Dmaven.test.skip=true -Dcheckstyle.skip=true

mvn -f "$REPO_DIR/java-iam/pom.xml" clean install -DskipTests -Dmaven.test.skip=true -Dcheckstyle.skip=true

mvn -f "$REPO_DIR/sdk-platform-java/java-core/pom.xml" clean install -DskipTests -Dmaven.test.skip=true -Dcheckstyle.skip=true

echo "Dependencies resolved successfully!"
