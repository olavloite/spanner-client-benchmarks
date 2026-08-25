import {Database, MutationSet} from '@google-cloud/spanner';
import {buildKeyName, buildRow} from './utils';

/**
 * Inserts initial records into Cloud Spanner in parallel using batched mutations.
 */
export async function populateData(
  database: Database,
  tableName: string,
  recordCount: number,
  zeroPadding: number,
  fieldCount: number,
  fieldLength: number,
  batchSize: number,
  threads: number,
  skipData = false,
): Promise<void> {
  if (skipData) {
    console.log('Skipping data population (--skip-data flag specified).');
    return;
  }

  const effectiveBatchSize = batchSize > 0 ? batchSize : 500;
  const effectiveThreads = threads > 0 ? threads : 16;
  const ranges = computePartitionRanges(recordCount, effectiveThreads);

  if (ranges.length === 0) {
    return;
  }

  let completedRecords = 0;
  let lastLogTime = Date.now();
  const startTime = Date.now();

  console.log(
    `Starting data population: ${recordCount} records across ${ranges.length} threads with batch size ${effectiveBatchSize}...`,
  );

  const logProgress = (count: number) => {
    const now = Date.now();
    if (now - lastLogTime >= 5000 || count === recordCount) {
      lastLogTime = now;
      const pct = ((count * 100.0) / recordCount).toFixed(1);
      const elapsedSeconds = Math.max(1, (now - startTime) / 1000);
      const rate = Math.floor(count / elapsedSeconds);
      console.log(
        `Progress: ${count} / ${recordCount} records (${pct}%) - ${rate} records/s`,
      );
    }
  };

  const workers = ranges.map(async range => {
    let batch: Array<Record<string, string>> = [];

    for (
      let recordIndex = range.start;
      recordIndex < range.end;
      recordIndex++
    ) {
      const key = buildKeyName(recordIndex, zeroPadding);
      const row = buildRow(key, fieldCount, fieldLength);
      batch.push(row);

      if (batch.length >= effectiveBatchSize) {
        const mutations = new MutationSet();
        mutations.upsert(tableName, batch);
        await database.writeAtLeastOnce(mutations);
        completedRecords += batch.length;
        logProgress(completedRecords);
        batch = [];
      }
    }

    if (batch.length > 0) {
      const mutations = new MutationSet();
      mutations.upsert(tableName, batch);
      await database.writeAtLeastOnce(mutations);
      completedRecords += batch.length;
      logProgress(completedRecords);
      batch = [];
    }
  });

  await Promise.all(workers);

  const totalDurationSeconds = Math.max(0.001, (Date.now() - startTime) / 1000);
  const finalRate = (recordCount / totalDurationSeconds).toFixed(2);
  console.log(
    `Data population complete: ${recordCount} records inserted in ${totalDurationSeconds.toFixed(3)}s (${finalRate} records/sec).`,
  );
}

interface PartitionRange {
  start: number;
  end: number;
}

function computePartitionRanges(
  recordCount: number,
  threads: number,
): PartitionRange[] {
  if (threads <= 0 || recordCount <= 0) {
    return [];
  }
  const effectiveThreads = Math.min(threads, recordCount);
  const baseChunk = Math.floor(recordCount / effectiveThreads);
  const remainder = recordCount % effectiveThreads;
  const ranges: PartitionRange[] = [];
  let currentStart = 0;

  for (let i = 0; i < effectiveThreads; i++) {
    const chunkSize = baseChunk + (i < remainder ? 1 : 0);
    ranges.push({
      start: currentStart,
      end: currentStart + chunkSize,
    });
    currentStart += chunkSize;
  }
  return ranges;
}
