package ycsb

import (
	"context"
	"fmt"
	"log"
	"sync"
	"sync/atomic"
	"time"

	"cloud.google.com/go/spanner"
	"golang.org/x/sync/errgroup"
)

type partitionRange struct {
	start int64
	end   int64
}

func computePartitionRanges(recordCount int64, threads int) []partitionRange {
	if threads <= 0 || recordCount <= 0 {
		return nil
	}
	if int64(threads) > recordCount {
		threads = int(recordCount)
	}

	baseChunk := recordCount / int64(threads)
	remainder := recordCount % int64(threads)
	ranges := make([]partitionRange, threads)
	var currentStart int64

	for i := 0; i < threads; i++ {
		chunkSize := baseChunk
		if int64(i) < remainder {
			chunkSize++
		}
		ranges[i] = partitionRange{
			start: currentStart,
			end:   currentStart + chunkSize,
		}
		currentStart += chunkSize
	}
	return ranges
}

// PopulateData inserts initial records into Cloud Spanner in parallel using batched mutations.
func PopulateData(ctx context.Context, client *spanner.Client, tableName string, recordCount int64, zeroPadding, fieldCount, fieldLength, batchSize, threads int) error {
	if batchSize <= 0 {
		batchSize = 500
	}
	if threads <= 0 {
		threads = 16
	}

	ranges := computePartitionRanges(recordCount, threads)
	if len(ranges) == 0 {
		return nil
	}

	var progress atomic.Int64
	var lastLogTime atomic.Int64
	lastLogTime.Store(time.Now().UnixNano())
	startTime := time.Now()

	colNames := make([]string, 0, fieldCount+1)
	colNames = append(colNames, "id")
	for f := 0; f < fieldCount; f++ {
		colNames = append(colNames, fmt.Sprintf("field%d", f))
	}

	log.Printf("Starting data population: %d records across %d threads with batch size %d...", recordCount, len(ranges), batchSize)

	group, groupCtx := errgroup.WithContext(ctx)

	for _, r := range ranges {
		start := r.start
		end := r.end

		group.Go(func() error {
			batch := make([]*spanner.Mutation, 0, batchSize)

			for recordIndex := start; recordIndex < end; recordIndex++ {
				if groupCtx.Err() != nil {
					return groupCtx.Err()
				}

				key := BuildKeyName(recordIndex, zeroPadding)
				colValues := make([]interface{}, 0, fieldCount+1)
				colValues = append(colValues, key)

				for f := 0; f < fieldCount; f++ {
					colValues = append(colValues, GenerateRandomString(fieldLength))
				}

				mutation := spanner.InsertOrUpdate(tableName, colNames, colValues)
				batch = append(batch, mutation)

				if len(batch) >= batchSize {
					_, err := client.Apply(groupCtx, batch, spanner.ApplyAtLeastOnce())
					if err != nil {
						return fmt.Errorf("failed to apply mutation batch at record %d: %w", recordIndex, err)
					}
					current := progress.Add(int64(len(batch)))
					logProgress(current, recordCount, startTime, &lastLogTime)
					batch = batch[:0]
				}
			}

			if len(batch) > 0 {
				_, err := client.Apply(groupCtx, batch, spanner.ApplyAtLeastOnce())
				if err != nil {
					return fmt.Errorf("failed to apply final mutation batch: %w", err)
				}
				current := progress.Add(int64(len(batch)))
				logProgress(current, recordCount, startTime, &lastLogTime)
			}

			return nil
		})
	}

	if err := group.Wait(); err != nil {
		return err
	}

	totalDuration := time.Since(startTime)
	rate := float64(recordCount) / totalDuration.Seconds()
	log.Printf("Data population complete: %d records inserted in %v (%.2f records/sec).", recordCount, totalDuration, rate)
	return nil
}

var logMu sync.Mutex

func logProgress(current, total int64, startTime time.Time, lastLogTime *atomic.Int64) {
	now := time.Now().UnixNano()
	prev := lastLogTime.Load()
	if now-prev > int64(5*time.Second) || current == total {
		if lastLogTime.CompareAndSwap(prev, now) {
			logMu.Lock()
			defer logMu.Unlock()
			pct := float64(current) * 100.0 / float64(total)
			elapsed := time.Since(startTime).Seconds()
			if elapsed < 1.0 {
				elapsed = 1.0
			}
			rate := int64(float64(current) / elapsed)
			fmt.Printf("Progress: %d / %d records (%.1f%%) - %d records/s\n", current, total, pct, rate)
		}
	}
}
