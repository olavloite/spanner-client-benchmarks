import './src/utils/disable-fetch';

import * as os from 'os';
import {Command} from 'commander';
import {
  diag,
  DiagConsoleLogger,
  DiagLogLevel,
  ValueType,
} from '@opentelemetry/api';

diag.setLogger(new DiagConsoleLogger(), DiagLogLevel.INFO);
import {
  setupMetrics,
  LATENCY_NAME,
  READ_LATENCY_NAME,
  OPERATION_COUNT_NAME,
  ERROR_COUNT_NAME,
  MEMORY_USAGE_NAME,
  CPU_UTILIZATION_NAME,
} from './src/metrics/otel';
import {createSpannerClient} from './src/spanner/client';
import {MockSpannerServer} from './src/spanner/mock-spanner';
import {PointSelectBenchmark} from './src/benchmarks/point-select';
import {SelectAndUpdateBenchmark} from './src/benchmarks/select-update';
import {ReadLargeResultSetBenchmark} from './src/benchmarks/read-large-result-set';
import {ReadNarrowResultSetBenchmark} from './src/benchmarks/read-narrow-result-set';
import {parseDuration} from './src/utils/duration';
import {AbstractBenchmark, LoadType} from './src/benchmarks/abstract-benchmark';

/**
 * Application entry point for Cloud Spanner Node.js client library performance benchmarks.
 */
async function main() {
  const program = new Command();

  program
    .name('spanner-node-benchmark')
    .description(
      'High-performance Spanner client library benchmark tool for Node.js (TypeScript)',
    )
    .version('1.0.0')
    // Core Global options (Required flags match picocli and Go flag setups)
    .requiredOption('-p, --project <projectId>', 'Google Cloud Project ID')
    .requiredOption('-i, --instance <instanceId>', 'Spanner Instance ID')
    .requiredOption('-d, --database <databaseId>', 'Spanner Database ID')
    .option(
      '--host <host>',
      'Custom Spanner endpoint URL override (e.g. for emulators)',
    )
    .option(
      '--duration <duration>',
      'Duration of the benchmark (e.g. 60s, 5m, 2h, inf). Defaults to inf (infinite).',
      'inf',
    )
    .option(
      '--for-alerting [value]',
      'Marks the metrics emitted for alerting/regression pipelines.',
      val => val === undefined || val === 'true' || val === '1',
      false,
    )
    .option(
      '--benchmark-name <benchmarkName>',
      'Optional name to identify this benchmark run in metrics',
      '',
    )
    .option(
      '--resource-probe-interval <resourceProbeInterval>',
      'Interval for probing resource usage (e.g. 10s, 1m). Set to 0 to disable',
      '10s',
    )
    .option(
      '--load-type <loadType>',
      'Load type (steady, spiky, gradual)',
      'steady',
    )
    .option(
      '--cycle-duration <cycleDuration>',
      'Duration of a full cycle for gradual load',
    )
    .option(
      '--peak-factor <peakFactor>',
      'Ratio of peak rate to average rate for gradual load',
    )
    .option(
      '--burst-factor <burstFactor>',
      'Ratio of burst rate to average rate',
    )
    .option(
      '--burst-duration <burstDuration>',
      'Average duration of a burst in seconds',
    )
    .option(
      '--burst-fraction <burstFraction>',
      'Fraction of total time spent in the burst state',
    )
    .option('--mock', 'Use local mock Spanner server', false)
    .option(
      '--no-metrics',
      'Disable metrics exporting (for testing purposes)',
      false,
    )
    .option(
      '--workers <workers>',
      'Number of parallel worker threads to execute the workload across. Defaults to auto (CPU cores - 1) if USE_SIDECAR=true, otherwise 1.',
    )
    .option('-t, --table <tableName>', 'Target database table name', 'test')
    .option('--tps <tps>', 'Target transactions per second throughput')
    .option(
      '--threads <threads>',
      'Parallel async worker pool concurrency limit',
    )
    .option('--num-rows <numRows>', 'Number of rows in target database table');

  // Point Select Workload Subcommand
  program
    .command('point-select')
    .description(
      'Execute the single point select workload (implicitly read-only single-use snapshot)',
    )
    .option('-t, --table <tableName>', 'Target database table name')
    .option('--tps <tps>', 'Target transactions per second throughput')
    .option(
      '--threads <threads>',
      'Parallel async worker pool concurrency limit',
    )
    .option('--num-rows <numRows>', 'Number of rows in target database table')
    .option(
      '--workers <workers>',
      'Number of parallel worker threads to execute the workload across',
    )
    .action(async subCommandOptions => {
      const globalOptions = program.opts();
      await runBenchmarkAction(
        'point-select',
        globalOptions,
        subCommandOptions,
      );
    });

  // Select and Update Workload Subcommand
  program
    .command('select-update')
    .description(
      'Execute the read-modify-write select and update workload inside Read-Write Transactions',
    )
    .option('-t, --table <tableName>', 'Target database table name')
    .option('--tps <tps>', 'Target transactions per second throughput')
    .option(
      '--threads <threads>',
      'Parallel async worker pool concurrency limit',
    )
    .option('--num-rows <numRows>', 'Number of rows in target database table')
    .option(
      '--workers <workers>',
      'Number of parallel worker threads to execute the workload across',
    )
    .action(async subCommandOptions => {
      const globalOptions = program.opts();
      await runBenchmarkAction(
        'select-update',
        globalOptions,
        subCommandOptions,
      );
    });

  // Read Large Result Set Workload Subcommand
  program
    .command('read-large-result-set')
    .description(
      'Execute the dynamic large result set iteration and client-side decoding workload scenario',
    )
    .option('-t, --table <tableName>', 'Target database table name')
    .option('--tps <tps>', 'Target transactions per second throughput')
    .option(
      '--threads <threads>',
      'Parallel async worker pool concurrency limit',
    )
    .option('--num-rows <numRows>', 'Number of rows to dynamically generate')
    .option(
      '--workers <workers>',
      'Number of parallel worker threads to execute the workload across',
    )
    .action(async subCommandOptions => {
      const globalOptions = program.opts();
      await runBenchmarkAction(
        'read-large-result-set',
        globalOptions,
        subCommandOptions,
      );
    });

  // Read Narrow Result Set Workload Subcommand
  program
    .command('read-narrow-result-set')
    .description(
      'Execute the dynamic narrow result set iteration and client-side decoding workload scenario',
    )
    .option('-t, --table <tableName>', 'Target database table name')
    .option('--tps <tps>', 'Target transactions per second throughput')
    .option(
      '--threads <threads>',
      'Parallel async worker pool concurrency limit',
    )
    .option('--num-rows <numRows>', 'Number of rows to dynamically generate')
    .option(
      '--workers <workers>',
      'Number of parallel worker threads to execute the workload across',
    )
    .action(async subCommandOptions => {
      const globalOptions = program.opts();
      await runBenchmarkAction(
        'read-narrow-result-set',
        globalOptions,
        subCommandOptions,
      );
    });

  // TPC-C Workload Subcommand
  program
    .command('tpcc')
    .description('Execute closed-loop TPC-C benchmark')
    .option(
      '--warehouses <warehouses>',
      'Scale factor (number of warehouses)',
      '1',
    )
    .option('--clients <clients>', 'Number of parallel worker clients', '10')
    .option('--items <items>', 'Number of items in catalog', '100000')
    .option(
      '--extended',
      'Run TPC-C benchmark with extended coverage of client library features',
      false,
    )
    .action(async subCommandOptions => {
      const globalOptions = program.opts();
      await runBenchmarkAction('tpcc', globalOptions, subCommandOptions);
    });

  await program.parseAsync(process.argv);
}

function validateAndFillLoadParams(loadType: LoadType, globalOpts: any) {
  let cycleDurationStr = globalOpts.cycleDuration;
  let peakFactor =
    globalOpts.peakFactor !== undefined
      ? parseFloat(globalOpts.peakFactor)
      : 2.0;
  const burstFactor =
    globalOpts.burstFactor !== undefined
      ? parseFloat(globalOpts.burstFactor)
      : 1.0;
  const burstDuration =
    globalOpts.burstDuration !== undefined
      ? parseFloat(globalOpts.burstDuration)
      : 1.0;
  const burstFraction =
    globalOpts.burstFraction !== undefined
      ? parseFloat(globalOpts.burstFraction)
      : 0.1;

  if (loadType === LoadType.Steady || loadType === LoadType.ClosedLoop) {
    if (
      globalOpts.cycleDuration !== undefined ||
      globalOpts.peakFactor !== undefined ||
      globalOpts.burstFactor !== undefined ||
      globalOpts.burstDuration !== undefined ||
      globalOpts.burstFraction !== undefined
    ) {
      console.error(
        'Error: Cannot specify burst or gradual load options when load-type is steady or closed-loop',
      );
      process.exit(1);
    }
  } else if (loadType === LoadType.Spiky) {
    if (
      globalOpts.cycleDuration !== undefined ||
      globalOpts.peakFactor !== undefined
    ) {
      console.error(
        'Error: Cannot specify gradual load options when load-type is spiky',
      );
      process.exit(1);
    }
  } else if (loadType === LoadType.Gradual) {
    if (
      globalOpts.burstFactor !== undefined ||
      globalOpts.burstDuration !== undefined ||
      globalOpts.burstFraction !== undefined
    ) {
      console.error(
        'Error: Cannot specify burst load options when load-type is gradual',
      );
      process.exit(1);
    }
    if (globalOpts.cycleDuration === undefined) cycleDurationStr = '1h';
    if (globalOpts.peakFactor === undefined) peakFactor = 2.0;
  }

  return {
    burstFactor,
    burstDuration,
    burstFraction,
    cycleDurationStr,
    peakFactor,
  };
}

async function startMockSpannerServer(
  tableName: string,
): Promise<{mockServer: MockSpannerServer; host: string}> {
  const mockServer = new MockSpannerServer();
  mockServer.registerAllMockResults(tableName);

  const port = await mockServer.start();
  const host = `127.0.0.1:${port}`;
  return {mockServer, host};
}

/**
 * Orchestrates the initialization and lifecycle of the benchmark execution.
 */
async function runBenchmarkAction(
  type:
    | 'point-select'
    | 'select-update'
    | 'read-large-result-set'
    | 'read-narrow-result-set'
    | 'tpcc',
  globalOpts: any,
  subOpts: any,
) {
  const projectId = globalOpts.project;
  const instanceId = globalOpts.instance;
  const databaseId = globalOpts.database;
  let host = globalOpts.host;
  const durationStr = globalOpts.duration;
  const forAlerting = globalOpts.forAlerting;
  const benchmarkName = globalOpts.benchmarkName;
  const loadType = globalOpts.loadType as LoadType;
  const {
    burstFactor,
    burstDuration,
    burstFraction,
    cycleDurationStr,
    peakFactor,
  } = validateAndFillLoadParams(loadType, globalOpts);

  const cycleDurationMs = parseDuration(cycleDurationStr);

  const defaultTps =
    type === 'read-large-result-set' || type === 'read-narrow-result-set'
      ? 0.05
      : 10.0;
  const defaultNumRows =
    type === 'read-large-result-set'
      ? 100000
      : type === 'read-narrow-result-set'
        ? 200000
        : 1000000;
  const defaultThreads = 100;

  const tableName = subOpts.table || globalOpts.table || 'test';
  // numRows defaults to 1,000,000 (or 100,000 / 200,000 for large/narrow result set).
  // Note: the second argument to parseInt is radix 10 (decimal).
  const numRows = subOpts.numRows
    ? parseInt(subOpts.numRows, 10)
    : globalOpts.numRows
      ? parseInt(globalOpts.numRows, 10)
      : defaultNumRows;
  const minId = 1;
  const maxId = numRows;
  const tps = subOpts.tps
    ? parseFloat(subOpts.tps)
    : globalOpts.tps
      ? parseFloat(globalOpts.tps)
      : defaultTps;
  const threads = subOpts.threads
    ? parseInt(subOpts.threads, 10)
    : globalOpts.threads
      ? parseInt(globalOpts.threads, 10)
      : defaultThreads;

  const cpuCount = process.env.BENCHMARK_CPU_LIMIT
    ? parseFloat(process.env.BENCHMARK_CPU_LIMIT)
    : os.availableParallelism
      ? os.availableParallelism()
      : os.cpus().length;
  const defaultWorkers =
    process.env.USE_SIDECAR === 'true'
      ? Math.max(1, Math.floor(cpuCount) - 1)
      : 1;
  const workersStr =
    subOpts.workers ?? globalOpts.workers ?? process.env.WORKERS;
  const workers = workersStr ? parseInt(workersStr, 10) : defaultWorkers;

  let mockServer: MockSpannerServer | undefined;
  if (globalOpts.mock) {
    const setup = await startMockSpannerServer(tableName);
    mockServer = setup.mockServer;
    host = setup.host;
    globalOpts.host = host;
  }

  // Discover if running in an emulator or metrics disabled environment
  const noMetrics =
    !!globalOpts.noMetrics ||
    !!process.env.SPANNER_EMULATOR_HOST ||
    (!globalOpts.mock &&
      !!host &&
      (host.includes('localhost:') || host.includes('127.0.0.1:')));

  // 1. Bootstrap OpenTelemetry Metrics Exporter
  const {meter, shutdown: shutdownMetrics} = setupMetrics(
    projectId,
    noMetrics,
    benchmarkName,
  );

  // Create the shared metric instruments (us units match Go/Java)
  const metricName =
    type === 'read-large-result-set' || type === 'read-narrow-result-set'
      ? READ_LATENCY_NAME
      : LATENCY_NAME;
  const latencyHistogram = meter.createHistogram(metricName, {
    description: 'Query latency measured in microseconds',
    unit: 'us',
  });

  const operationCounter = meter.createCounter(OPERATION_COUNT_NAME, {
    description: 'Total number of benchmark operations executed',
    unit: '1',
    valueType: ValueType.INT,
  });

  const errorCounter = meter.createCounter(ERROR_COUNT_NAME, {
    description:
      'Total number of benchmark operations that failed with an error',
    unit: '1',
    valueType: ValueType.INT,
  });

  const memoryUsageHistogram = meter.createHistogram(MEMORY_USAGE_NAME, {
    description: 'Active memory usage in bytes',
    unit: 'By',
  });

  const cpuUtilizationHistogram = meter.createHistogram(CPU_UTILIZATION_NAME, {
    description: 'Process CPU utilization',
    unit: '1',
  });

  // 2. Bootstrap Google Cloud Spanner Client
  const spanner = createSpannerClient(projectId, host);
  const instance = spanner.instance(instanceId);
  const database = instance.database(databaseId);

  // 3. Instantiate the designated concrete benchmark workload task
  const {TpccBenchmarkRunner} = require('./src/benchmarks/tpcc/benchmark');
  let benchmark: any;
  const parsedDurationMs = parseDuration(durationStr);

  if (type === 'point-select') {
    benchmark = new PointSelectBenchmark(
      database,
      latencyHistogram,
      operationCounter,
      errorCounter,
      memoryUsageHistogram,
      cpuUtilizationHistogram,
      globalOpts.resourceProbeInterval,
      tableName,
      minId,
      maxId,
      tps,
      threads,
      parsedDurationMs,
      forAlerting,
      benchmarkName,
      loadType,
      cycleDurationMs,
      peakFactor,
      burstFactor,
      burstDuration,
      burstFraction,
      globalOpts.mock,
      workers,
      host,
    );
  } else if (type === 'select-update') {
    benchmark = new SelectAndUpdateBenchmark(
      database,
      latencyHistogram,
      operationCounter,
      errorCounter,
      memoryUsageHistogram,
      cpuUtilizationHistogram,
      globalOpts.resourceProbeInterval,
      tableName,
      minId,
      maxId,
      tps,
      threads,
      parsedDurationMs,
      forAlerting,
      benchmarkName,
      loadType,
      cycleDurationMs,
      peakFactor,
      burstFactor,
      burstDuration,
      burstFraction,
      globalOpts.mock,
      workers,
      host,
    );
  } else if (type === 'read-large-result-set') {
    benchmark = new ReadLargeResultSetBenchmark(
      database,
      latencyHistogram,
      operationCounter,
      errorCounter,
      memoryUsageHistogram,
      cpuUtilizationHistogram,
      globalOpts.resourceProbeInterval,
      tableName,
      minId,
      maxId,
      tps,
      threads,
      parsedDurationMs,
      forAlerting,
      benchmarkName,
      numRows,
      loadType,
      cycleDurationMs,
      peakFactor,
      burstFactor,
      burstDuration,
      burstFraction,
      globalOpts.mock,
      workers,
      host,
    );
  } else if (type === 'read-narrow-result-set') {
    benchmark = new ReadNarrowResultSetBenchmark(
      database,
      latencyHistogram,
      operationCounter,
      errorCounter,
      memoryUsageHistogram,
      cpuUtilizationHistogram,
      globalOpts.resourceProbeInterval,
      tableName,
      minId,
      maxId,
      tps,
      threads,
      parsedDurationMs,
      forAlerting,
      benchmarkName,
      numRows,
      loadType,
      cycleDurationMs,
      peakFactor,
      burstFactor,
      burstDuration,
      burstFraction,
      globalOpts.mock,
      workers,
      host,
    );
  } else if (type === 'tpcc') {
    const warehouses = parseInt(subOpts.warehouses, 10);
    const clients = parseInt(subOpts.clients, 10);
    const items = parseInt(subOpts.items, 10);
    const extended = !!subOpts.extended;
    benchmark = new TpccBenchmarkRunner(
      database,
      latencyHistogram,
      operationCounter,
      errorCounter,
      memoryUsageHistogram,
      cpuUtilizationHistogram,
      globalOpts.resourceProbeInterval,
      warehouses,
      clients,
      items,
      parsedDurationMs,
      forAlerting,
      benchmarkName,
      extended,
    );
  } else {
    console.error(
      `Error: Unsupported benchmark type: '${type}'. Valid options are: 'point-select', 'select-update', 'read-large-result-set', 'tpcc'.`,
    );
    process.exit(1);
  }

  // 4. Wire Up Graceful Process Termination Signals (SIGINT, SIGTERM)
  let isTerminating = false;
  const shutdownLifecycle = async (signal: string) => {
    if (isTerminating) return;
    isTerminating = true;
    console.log(
      `\n[Lifecycle] Received signal ${signal}. Initiating graceful shutdown...`,
    );

    benchmark.stop();

    try {
      await spanner.close();
      console.log('[Lifecycle] Spanner client connections closed.');
    } catch (err) {
      console.error('[Lifecycle] Error closing Spanner client:', err);
    }

    if (mockServer) {
      try {
        await mockServer.stop();
        console.log('[Lifecycle] Mock Spanner server stopped.');
      } catch (e) {}
    }

    await shutdownMetrics();
    console.log('[Lifecycle] Termination complete. Exiting.');
    process.exit(0);
  };

  process.on('SIGINT', () => shutdownLifecycle('SIGINT'));
  process.on('SIGTERM', () => shutdownLifecycle('SIGTERM'));

  // 5. Run the Workload Execution Engine
  try {
    await benchmark.run();
  } catch (err) {
    console.error('Fatal error during benchmark execution loop:', err);
  } finally {
    // Execute standard cleanup if we finished normal duration instead of signal kill
    if (!isTerminating) {
      isTerminating = true;
      console.log('[Cleanup] Closing Spanner client...');
      try {
        spanner.close();
      } catch (e) {}
      console.log('[Cleanup] Stopping Mock Spanner server...');
      if (mockServer) {
        try {
          await mockServer.stop();
        } catch (e) {}
      }
      console.log('[Cleanup] Shutting down OpenTelemetry metrics...');
      try {
        await Promise.race([
          shutdownMetrics(),
          new Promise<void>(resolve => setTimeout(resolve, 2000)),
        ]);
      } catch (e) {}
      process.exit(0);
    }
  }
}

// Execute application
main().catch(err => {
  console.error('Unhandled fatal exception in main context:', err);
  process.exit(1);
});
