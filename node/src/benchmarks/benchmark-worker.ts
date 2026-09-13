/**
 * Multi-Threaded Benchmark Worker Engine for Node.js Cloud Spanner Benchmarks.
 *
 * Architecture & Execution Model:
 * --------------------------------
 * Node.js runs a single-threaded JavaScript execution model (libuv event loop) per process.
 * To achieve true multi-core CPU scaling comparable to multi-threaded runtimes (Go, Java, Rust)
 * on multi-vCPU machines (such as 4-vCPU GCE VMs), this module runs inside a dedicated
 * `worker_threads` Worker.
 *
 * Key Concepts:
 * 1. Isolated V8 Engine & Event Loop:
 *    Each Worker thread runs in its own V8 isolate with an independent event loop and libuv thread pool.
 * 2. Dedicated Spanner Client Instance:
 *    Each worker initializes its own `Spanner` client instance and gRPC channel pool.
 *    This avoids cross-thread synchronization overhead, lock contention, and event-loop lag.
 * 3. Coordinator-Worker Communication Protocol:
 *    - Main Thread (Coordinator): Responsible for benchmark timing, rate-limiting (TPS), burst schedules,
 *      concurrency throttling, and metric aggregation/OpenTelemetry export.
 *    - Worker Thread: Receives execution commands via MessagePort, executes database operations,
 *      computes high-resolution execution latency using `process.hrtime.bigint()`, and reports results.
 *
 * Message Protocol:
 * -----------------
 * Main -> Worker:
 *   - `{ type: 'execute', taskId: number }`
 *     Request to execute a single benchmark operation.
 *   - `{ type: 'stop' }`
 *     Request to gracefully stop after completing in-flight queries.
 *
 * Worker -> Main:
 *   - `{ type: 'ready', workerId: number }`
 *     Notifies coordinator that client initialization is complete and worker is ready for work.
 *   - `{ type: 'completed', taskId: number, latencyUs: number, success: boolean, error?: string }`
 *     Reports result and high-resolution latency for an executed task.
 */

import {parentPort, workerData} from 'worker_threads';
import {Database} from '@google-cloud/spanner';
import {createSpannerClient} from '../spanner/client';
import {executePointSelect} from './point-select';
import {executeSelectAndUpdate} from './select-update';
import {executeReadLargeResultSet} from './read-large-result-set';
import {executeReadNarrowResultSet} from './read-narrow-result-set';

/**
 * Worker initialization data transferred from the main thread via `workerData`.
 * Note: workerData uses the HTML structured clone algorithm, so values must be serializable
 * primitives or plain objects (no functions, sockets, or complex classes).
 */
export interface BenchmarkWorkerData {
  workerId: number;
  benchmarkType: string;
  projectId: string;
  instanceId: string;
  databaseId: string;
  host?: string;
  tableName: string;
  minId: number;
  maxId: number;
  numRows?: number;
  databaseOptions?: Record<string, any>;
}

if (!parentPort) {
  throw new Error('benchmark-worker must be executed as a worker thread');
}

const data = workerData as BenchmarkWorkerData;

// Each worker thread establishes its own Spanner client
const spanner = createSpannerClient(data.projectId, data.host);
const instance = spanner.instance(data.instanceId);
const database = instance.database(data.databaseId, data.databaseOptions);

type ExecutorFn = (
  database: Database,
  tableName: string,
  minId: number,
  maxId: number,
) => Promise<number | void>;

let executeFn: ExecutorFn;

switch (data.benchmarkType) {
  case 'point-select':
    executeFn = executePointSelect;
    break;
  case 'select-update':
    executeFn = executeSelectAndUpdate;
    break;
  case 'read-large-result-set':
    executeFn = (db: Database) =>
      executeReadLargeResultSet(db, data.numRows || 100000);
    break;
  case 'read-narrow-result-set':
    executeFn = (db: Database) =>
      executeReadNarrowResultSet(db, data.numRows || 200000);
    break;
  default:
    throw new Error(
      `Unsupported benchmark type in worker: ${data.benchmarkType}`,
    );
}

// Track concurrent in-flight tasks assigned to this worker so we can cleanly
// drain all active queries before closing the database and exiting.
let inFlightTasks = 0;
let isStopping = false;

/**
 * Closes Spanner database and client connections cleanly before terminating.
 */
async function cleanup(): Promise<void> {
  try {
    await database.close();
  } catch (e) {
    // Ignore close errors during process teardown
  }
  try {
    await spanner.close();
  } catch (e) {
    // Ignore close errors during process teardown
  }
  process.exit(0);
}

// Handle incoming control and execution messages from the main coordinator thread
parentPort.on('message', async (msg: any) => {
  if (!msg || typeof msg !== 'object') {
    return;
  }

  if (msg.type === 'execute') {
    inFlightTasks++;
    const startTimeNs = process.hrtime.bigint();
    try {
      const customLatencyUs = await executeFn(
        database,
        data.tableName,
        data.minId,
        data.maxId,
      );
      const endTimeNs = process.hrtime.bigint();
      const latencyUs =
        typeof customLatencyUs === 'number' && customLatencyUs > 0
          ? customLatencyUs
          : Number(endTimeNs - startTimeNs) / 1000;

      parentPort?.postMessage({
        type: 'completed',
        taskId: msg.taskId,
        latencyUs,
        success: true,
      });
    } catch (err: any) {
      const endTimeNs = process.hrtime.bigint();
      const latencyUs = Number(endTimeNs - startTimeNs) / 1000;
      parentPort?.postMessage({
        type: 'completed',
        taskId: msg.taskId,
        latencyUs,
        success: false,
        error: err?.message || String(err),
      });
    } finally {
      inFlightTasks--;
      if (isStopping && inFlightTasks === 0) {
        await cleanup();
      }
    }
  } else if (msg.type === 'stop') {
    isStopping = true;
    if (inFlightTasks === 0) {
      await cleanup();
    }
  }
});

// Signal readiness to coordinator thread
parentPort.postMessage({
  type: 'ready',
  workerId: data.workerId,
});
