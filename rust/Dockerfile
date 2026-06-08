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
ENTRYPOINT ["./benchmark"]
