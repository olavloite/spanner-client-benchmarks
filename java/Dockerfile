# Stage 0: Build the Go sidecar generator
FROM golang:1.27-alpine AS generator-build
WORKDIR /build
COPY generator/ .
RUN CGO_ENABLED=0 go build -o workload-generator .

# Stage 1: Build Java application
FROM maven:3.9.15-eclipse-temurin-26 AS build
WORKDIR /app

# Install git and python3
RUN apt-get update && apt-get install -y git python3

# Copy the build script and source code
COPY build_app.sh .
COPY resolve_spanner_dependencies.sh .
COPY pom.xml .
COPY src ./src

ARG USE_RELEASED_VERSION=false
ARG CLIENT_BRANCH=main
ARG CLIENT_REPO=""
ENV USE_RELEASED_VERSION=$USE_RELEASED_VERSION
ENV CLIENT_BRANCH=$CLIENT_BRANCH
ENV CLIENT_REPO=$CLIENT_REPO

# Run the build script
RUN chmod +x build_app.sh && ./build_app.sh

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/spanner-java-benchmark-1.0-SNAPSHOT.jar app.jar
COPY --from=build /app/target/lib ./lib

COPY --from=generator-build /build/workload-generator /usr/local/bin/workload-generator
COPY entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh
ENTRYPOINT ["./entrypoint.sh", "java", "-jar", "app.jar"]
