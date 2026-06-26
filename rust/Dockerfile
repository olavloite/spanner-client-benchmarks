# Stage 0: Build the Go sidecar generator
FROM golang:1.26-alpine AS generator-build
WORKDIR /build
COPY generator/ .
RUN CGO_ENABLED=0 go build -o workload-generator .

# Stage 1: Build Rust application
FROM rust:slim-bookworm AS build
WORKDIR /app

# Install compilation prerequisites
RUN apt-get update && apt-get install -y --no-install-recommends pkg-config libssl-dev git && rm -rf /var/lib/apt/lists/*

COPY build_app.sh .
COPY Cargo.toml ./
COPY src ./src

ARG USE_RELEASED_VERSION=false
ARG CLIENT_BRANCH=main
ARG CLIENT_REPO=""
ENV USE_RELEASED_VERSION=$USE_RELEASED_VERSION
ENV CLIENT_BRANCH=$CLIENT_BRANCH
ENV CLIENT_REPO=$CLIENT_REPO

RUN chmod +x build_app.sh && ./build_app.sh

# Final slim runner stage
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates libssl-dev && rm -rf /var/lib/apt/lists/*
WORKDIR /root/
COPY --from=build /app/target/release/spanner-rust-benchmark ./benchmark

COPY --from=generator-build /build/workload-generator /usr/local/bin/workload-generator
COPY entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh
ENTRYPOINT ["./entrypoint.sh", "./benchmark"]
