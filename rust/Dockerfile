FROM rust:slim AS build
WORKDIR /app

# Install compilation prerequisites
RUN apt-get update && apt-get install -y --no-install-recommends pkg-config libssl-dev && rm -rf /var/lib/apt/lists/*

# Pre-compile dependency cache
COPY Cargo.toml ./
RUN mkdir src && echo "fn main() {}" > src/main.rs
RUN cargo build --release
RUN rm -f target/release/deps/spanner_rust_benchmark*

# Copy actual code and compile release binary
COPY src ./src
RUN cargo build --release

# Final slim runner stage
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates libssl-dev && rm -rf /var/lib/apt/lists/*
WORKDIR /root/
COPY --from=build /app/target/release/spanner-rust-benchmark ./benchmark
ENTRYPOINT ["./benchmark"]
