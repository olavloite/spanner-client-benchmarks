#!/bin/sh
set -e

# Opt-in Go sidecar generator orchestration
if [ "$USE_SIDECAR" = "true" ] && [ "$LOAD_TYPE" != "closed-loop" ]; then
  echo "Orchestrating Go-based workload generator sidecar..."
  SOCKET_PATH="/tmp/benchmark.sock"

  # Build the argument string for the generator
  GENERATOR_ARGS="--socket-path=$SOCKET_PATH"

  if [ -n "$LOAD_TYPE" ]; then
    GENERATOR_ARGS="$GENERATOR_ARGS --load-type=$LOAD_TYPE"
  fi
  if [ -n "$TPS" ]; then
    GENERATOR_ARGS="$GENERATOR_ARGS --tps=$TPS"
  fi
  if [ -n "$DURATION" ]; then
    GENERATOR_ARGS="$GENERATOR_ARGS --duration=$DURATION"
  fi

  if [ -n "$CYCLE_DURATION" ]; then
    GENERATOR_ARGS="$GENERATOR_ARGS --cycle-duration=$CYCLE_DURATION"
  fi
  if [ -n "$PEAK_FACTOR" ]; then
    GENERATOR_ARGS="$GENERATOR_ARGS --peak-factor=$PEAK_FACTOR"
  fi
  if [ -n "$BURST_FACTOR" ]; then
    GENERATOR_ARGS="$GENERATOR_ARGS --burst-factor=$BURST_FACTOR"
  fi
  if [ -n "$BURST_DURATION" ]; then
    GENERATOR_ARGS="$GENERATOR_ARGS --burst-duration=$BURST_DURATION"
  fi
  if [ -n "$BURST_FRACTION" ]; then
    GENERATOR_ARGS="$GENERATOR_ARGS --burst-fraction=$BURST_FRACTION"
  fi

  # Start the Go workload-generator in the background
  echo "Starting workload-generator: workload-generator $GENERATOR_ARGS"
  workload-generator $GENERATOR_ARGS &
  GENERATOR_PID=$!

  # Wait for the socket file to be created before launching the benchmark client
  echo "Waiting for sidecar socket to be created..."
  for i in $(seq 1 30); do
    if [ -S "$SOCKET_PATH" ]; then
      break
    fi
    sleep 0.1
  done

  if [ ! -S "$SOCKET_PATH" ]; then
    echo "ERROR: Sidecar socket was not created within 3 seconds!" >&2
    kill $GENERATOR_PID 2>/dev/null || true
    exit 1
  fi
  echo "Sidecar socket is ready. Starting benchmark client..."

  # Export socket path for the client benchmark app
  export SPANNER_BENCHMARK_SOCKET="$SOCKET_PATH"

  # Clean up background generator if main process terminates
  trap 'kill $GENERATOR_PID 2>/dev/null || true' EXIT
fi

# Execute the actual benchmark application command (replaces PID 1)
exec "$@"
