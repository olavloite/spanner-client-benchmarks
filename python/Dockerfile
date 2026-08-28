# Stage 0: Build the Go sidecar generator
FROM golang:1.27-alpine AS generator-build
WORKDIR /build
COPY generator/ .
RUN CGO_ENABLED=0 go build -o workload-generator .

# Stage 1: Build dependencies in virtual environment
FROM python:3.14-slim AS build
WORKDIR /app

# Install git for cloning Spanner client
RUN apt-get update && apt-get install -y git

# Create virtual environment
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

# Copy build scripts and source code
COPY build_app.sh .
COPY requirements.txt .
COPY main.py .
COPY src/ ./src/

ARG USE_RELEASED_VERSION=false
ARG CLIENT_BRANCH=main
ARG CLIENT_REPO=""
ENV USE_RELEASED_VERSION=$USE_RELEASED_VERSION
ENV CLIENT_BRANCH=$CLIENT_BRANCH
ENV CLIENT_REPO=$CLIENT_REPO

# Run build script to build Spanner client from source and install all dependencies
RUN chmod +x build_app.sh && ./build_app.sh

# Stage 2: Final runtime container
FROM python:3.14-slim
WORKDIR /app

# Copy virtual environment from build stage
COPY --from=build /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

# Copy application code
COPY --from=build /app/main.py .
COPY --from=build /app/src/ ./src/

COPY --from=generator-build /build/workload-generator /usr/local/bin/workload-generator
COPY entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh
ENTRYPOINT ["./entrypoint.sh", "python", "main.py"]
