#!/bin/sh
set -e

# Opt-in Go sidecar generator orchestration
if [ "$USE_SIDECAR" = "true" ] && [ "$LOAD_TYPE" != "closed-loop" ]; then
  echo "Orchestrating Go-based workload generator sidecar..."
  SOCKET_PATH="/tmp/benchmark.sock"

  # Build the argument string for the generator
  GENERATOR_ARGS="--socket-path=$SOCKET_PATH"

  if [ -n "$DURATION" ]; then
    GENERATOR_ARGS="$GENERATOR_ARGS --duration=$DURATION"
  fi

  if [ -n "$LOAD_TYPE" ]; then
    GENERATOR_ARGS="$GENERATOR_ARGS --load-type=$LOAD_TYPE"
  fi
  if [ -n "$TPS" ]; then
    GENERATOR_ARGS="$GENERATOR_ARGS --tps=$TPS"
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
    case "$BURST_DURATION" in
      *[a-zA-Z]*) GENERATOR_ARGS="$GENERATOR_ARGS --burst-duration=$BURST_DURATION" ;;
      *) GENERATOR_ARGS="$GENERATOR_ARGS --burst-duration=${BURST_DURATION}s" ;;
    esac
  fi
  if [ -n "$BURST_FRACTION" ]; then
    GENERATOR_ARGS="$GENERATOR_ARGS --burst-fraction=$BURST_FRACTION"
  fi

  # Start the Go workload-generator in the background with CPU affinity if possible
  CPU_COUNT=$(getconf _NPROCESSORS_ONLN || echo 1)
  if [ "$CPU_COUNT" -gt 1 ] && command -v taskset >/dev/null 2>&1; then
    GEN_CPUS="0"
    
    # Build list of CPUs for client (all CPUs except CPU 0)
    CLIENT_CPUS=""
    i=1
    while [ "$i" -lt "$CPU_COUNT" ]; do
      if [ -z "$CLIENT_CPUS" ]; then CLIENT_CPUS="$i"; else CLIENT_CPUS="$CLIENT_CPUS,$i"; fi
      i=$((i + 1))
    done
    
    echo "Isolating processes: Generator bound to CPU [$GEN_CPUS], Client bound to CPUs [$CLIENT_CPUS]"
    taskset -c "$GEN_CPUS" workload-generator $GENERATOR_ARGS &
    GENERATOR_PID=$!
    
    export SPANNER_BENCHMARK_SOCKET="$SOCKET_PATH"
    trap 'kill $GENERATOR_PID 2>/dev/null || true' EXIT
    
    exec taskset -c "$CLIENT_CPUS" "$@"
  else
    echo "Starting workload-generator: workload-generator $GENERATOR_ARGS"
    workload-generator $GENERATOR_ARGS &
    GENERATOR_PID=$!
    export SPANNER_BENCHMARK_SOCKET="$SOCKET_PATH"
    trap 'kill $GENERATOR_PID 2>/dev/null || true' EXIT
    exec "$@"
  fi
else
  # Execute the actual benchmark application command (replaces PID 1)
  exec "$@"
fi
