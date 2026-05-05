# Stage 1: Build dependencies in virtual environment
FROM python:3.10-slim AS build
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

# Run build script to build Spanner client from source and install all dependencies
RUN chmod +x build_app.sh && ./build_app.sh

# Stage 2: Final runtime container
FROM python:3.10-slim
WORKDIR /app

# Copy virtual environment from build stage
COPY --from=build /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

# Copy application code
COPY --from=build /app/main.py .
COPY --from=build /app/src/ ./src/

ENTRYPOINT ["python", "main.py"]
