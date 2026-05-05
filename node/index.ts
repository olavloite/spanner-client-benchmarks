import { Command } from "commander";
import { setupMetrics, LATENCY_NAME } from "./src/metrics/otel";
import { createSpannerClient } from "./src/spanner/client";
import { PointSelectBenchmark } from "./src/benchmarks/point-select";
import { SelectAndUpdateBenchmark } from "./src/benchmarks/select-update";
import { parseDuration } from "./src/config/duration";
import { AbstractBenchmark } from "./src/benchmarks/abstract-benchmark";

/**
 * Application entry point for Cloud Spanner Node.js client library performance benchmarks.
 */
async function main() {
  const program = new Command();

  program
    .name("spanner-node-benchmark")
    .description("High-performance Spanner client library benchmark tool for Node.js (TypeScript)")
    .version("1.0.0")
    // Core Global options (Required flags match picocli and Go flag setups)
    .requiredOption("-p, --project <projectId>", "Google Cloud Project ID")
    .requiredOption("-i, --instance <instanceId>", "Spanner Instance ID")
    .requiredOption("-d, --database <databaseId>", "Spanner Database ID")
    .option("--host <host>", "Custom Spanner endpoint URL override (e.g. for emulators)")
    .option(
      "--duration <duration>",
      "Duration of the benchmark (e.g. 60s, 5m, 2h, inf). Defaults to inf (infinite).",
      "inf"
    )
    .option("--for-alerting", "Marks the metrics emitted for alerting/regression pipelines.", false);

  // Point Select Workload Subcommand
  program
    .command("point-select")
    .description("Execute the single point select workload (implicitly read-only single-use snapshot)")
    .requiredOption("-t, --table <tableName>", "Target database table name")
    .option("--min-id <id>", "Minimum row identifier primary key boundary", "1")
    .option("--max-id <id>", "Maximum row identifier primary key boundary", "1000000")
    .option("--tps <tps>", "Target transactions per second throughput", "1")
    .option("--threads <threads>", "Parallel async worker pool concurrency limit", "100")
    .action(async (subCommandOptions) => {
      const globalOptions = program.opts();
      await runBenchmarkAction("point-select", globalOptions, subCommandOptions);
    });

  // Select and Update Workload Subcommand
  program
    .command("select-update")
    .description("Execute the read-modify-write select and update workload inside Read-Write Transactions")
    .requiredOption("-t, --table <tableName>", "Target database table name")
    .option("--min-id <id>", "Minimum row identifier primary key boundary", "1")
    .option("--max-id <id>", "Maximum row identifier primary key boundary", "1000000")
    .option("--tps <tps>", "Target transactions per second throughput", "1")
    .option("--threads <threads>", "Parallel async worker pool concurrency limit", "100")
    .action(async (subCommandOptions) => {
      const globalOptions = program.opts();
      await runBenchmarkAction("select-update", globalOptions, subCommandOptions);
    });

  await program.parseAsync(process.argv);
}

/**
 * Orchestrates the initialization and lifecycle of the benchmark execution.
 */
async function runBenchmarkAction(
  type: "point-select" | "select-update",
  globalOpts: any,
  subOpts: any
) {
  const projectId = globalOpts.project;
  const instanceId = globalOpts.instance;
  const databaseId = globalOpts.database;
  const host = globalOpts.host;
  const durationStr = globalOpts.duration;
  const forAlerting = globalOpts.forAlerting;

  const tableName = subOpts.table;
  const minId = parseInt(subOpts.minId, 10);
  const maxId = parseInt(subOpts.maxId, 10);
  const tps = parseFloat(subOpts.tps);
  const threads = parseInt(subOpts.threads, 10);

  // Discover if running in an emulator environment
  const isEmulator =
    !!process.env.SPANNER_EMULATOR_HOST ||
    (!!host && (host.includes("localhost:") || host.includes("127.0.0.1:")));

  // 1. Bootstrap OpenTelemetry Metrics Exporter
  const { meter, shutdown: shutdownMetrics } = setupMetrics(projectId, isEmulator);

  // Create the shared histogram metric instrument (us units match Go/Java)
  const latencyHistogram = meter.createHistogram(LATENCY_NAME, {
    description: "Query latency measured in microseconds",
    unit: "us",
  });

  // 2. Bootstrap Google Cloud Spanner Client
  const spanner = createSpannerClient(projectId, host);
  const instance = spanner.instance(instanceId);
  const database = instance.database(databaseId);

  // 3. Instantiate the designated concrete benchmark workload task
  let benchmark: AbstractBenchmark;
  const parsedDurationMs = parseDuration(durationStr);

  if (type === "point-select") {
    benchmark = new PointSelectBenchmark(
      database,
      latencyHistogram,
      tableName,
      minId,
      maxId,
      tps,
      threads,
      parsedDurationMs,
      forAlerting
    );
  } else {
    benchmark = new SelectAndUpdateBenchmark(
      database,
      latencyHistogram,
      tableName,
      minId,
      maxId,
      tps,
      threads,
      parsedDurationMs,
      forAlerting
    );
  }

  // 4. Wire Up Graceful Process Termination Signals (SIGINT, SIGTERM)
  let isTerminating = false;
  const shutdownLifecycle = async (signal: string) => {
    if (isTerminating) return;
    isTerminating = true;
    console.log(`\n[Lifecycle] Received signal ${signal}. Initiating graceful shutdown...`);

    benchmark.stop();

    try {
      await spanner.close();
      console.log("[Lifecycle] Spanner client connections closed.");
    } catch (err) {
      console.error("[Lifecycle] Error closing Spanner client:", err);
    }

    await shutdownMetrics();
    console.log("[Lifecycle] Termination complete. Exiting.");
    process.exit(0);
  };

  process.on("SIGINT", () => shutdownLifecycle("SIGINT"));
  process.on("SIGTERM", () => shutdownLifecycle("SIGTERM"));

  // 5. Run the Workload Execution Engine
  try {
    await benchmark.run();
  } catch (err) {
    console.error("Fatal error during benchmark execution loop:", err);
  } finally {
    // Execute standard cleanup if we finished normal duration instead of signal kill
    if (!isTerminating) {
      isTerminating = true;
      try {
        await spanner.close();
      } catch (e) {}
      await shutdownMetrics();
    }
  }
}

// Execute application
main().catch((err) => {
  console.error("Unhandled fatal exception in main context:", err);
  process.exit(1);
});
