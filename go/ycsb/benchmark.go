package ycsb

import (
	"context"
	"fmt"
	"math/rand/v2"
	"strings"
	"sync/atomic"
	"time"

	"cloud.google.com/go/spanner"
	"google.golang.org/api/iterator"
)

// YcsbBenchmarkState manages YCSB workload generation, operation routing, and latency tracking.
type YcsbBenchmarkState struct {
	workload                  Workload
	distribution              KeyDistribution
	recordCount               int64
	zeroPadding               int
	fieldCount                int
	fieldLength               int
	useReadRow                bool
	isMock                    bool
	tableName                 string
	zipfianGenerator          *ZipfianGenerator
	scrambledZipfianGenerator *ScrambledZipfianGenerator
	skewedLatestGenerator     *SkewedLatestGenerator
	insertKeySequence         *atomic.Int64
	fieldNames                []string
	insertColNames            []string
	readSQL                   string
	scanSQL                   string

	// Metrics
	readTotalDurationNs   atomic.Uint64
	readOperationCount    atomic.Uint64
	updateTotalDurationNs atomic.Uint64
	updateOperationCount  atomic.Uint64
	insertTotalDurationNs atomic.Uint64
	insertOperationCount  atomic.Uint64
	scanTotalDurationNs   atomic.Uint64
	scanOperationCount    atomic.Uint64
	rmwTotalDurationNs    atomic.Uint64
	rmwOperationCount     atomic.Uint64
}

// NewYcsbBenchmarkState creates and initializes a new YcsbBenchmarkState.
func NewYcsbBenchmarkState(
	workload Workload,
	distribution KeyDistribution,
	recordCount int64,
	zeroPadding int,
	fieldCount int,
	fieldLength int,
	useReadRow bool,
	isMock bool,
	tableName string,
) *YcsbBenchmarkState {
	if fieldCount <= 0 {
		fieldCount = 10
	}
	if fieldLength <= 0 {
		fieldLength = 100
	}
	if recordCount <= 0 {
		recordCount = 100000
	}
	if zeroPadding < 0 {
		zeroPadding = 12
	}
	if tableName == "" {
		tableName = "usertable"
	}

	fieldNames := make([]string, fieldCount)
	for i := 0; i < fieldCount; i++ {
		fieldNames[i] = fmt.Sprintf("field%d", i)
	}

	insertColNames := make([]string, 0, fieldCount+1)
	insertColNames = append(insertColNames, "id")
	insertColNames = append(insertColNames, fieldNames...)

	readSQL := fmt.Sprintf("SELECT %s FROM %s WHERE id = @id", strings.Join(fieldNames, ", "), tableName)
	scanSQL := fmt.Sprintf("SELECT %s FROM %s WHERE id >= @startKey ORDER BY id LIMIT @scanLength", strings.Join(fieldNames, ", "), tableName)

	insertKeySeq := &atomic.Int64{}
	insertKeySeq.Store(recordCount)

	return &YcsbBenchmarkState{
		workload:                  workload,
		distribution:              distribution,
		recordCount:               recordCount,
		zeroPadding:               zeroPadding,
		fieldCount:                fieldCount,
		fieldLength:               fieldLength,
		useReadRow:                useReadRow,
		isMock:                    isMock,
		tableName:                 tableName,
		zipfianGenerator:          NewZipfianGeneratorRange(0, recordCount-1),
		scrambledZipfianGenerator: NewScrambledZipfianGenerator(0, recordCount-1),
		skewedLatestGenerator:     NewSkewedLatestGenerator(insertKeySeq),
		insertKeySequence:         insertKeySeq,
		fieldNames:                fieldNames,
		insertColNames:            insertColNames,
		readSQL:                   readSQL,
		scanSQL:                   scanSQL,
	}
}

// Workload returns the configured YCSB workload.
func (s *YcsbBenchmarkState) Workload() Workload {
	return s.workload
}

// TableName returns the table name.
func (s *YcsbBenchmarkState) TableName() string {
	return s.tableName
}

func (s *YcsbBenchmarkState) getRandomKey() string {
	if s.isMock {
		return BuildKeyName(0, s.zeroPadding)
	}
	switch s.distribution {
	case DistributionScrambledZipfian:
		return s.scrambledZipfianGenerator.NextKey(s.zeroPadding)
	case DistributionZipfian:
		return s.zipfianGenerator.NextKey(s.zeroPadding)
	case DistributionUniform:
		val := rand.Int64N(s.recordCount)
		return BuildKeyName(val, s.zeroPadding)
	default:
		return s.scrambledZipfianGenerator.NextKey(s.zeroPadding)
	}
}

// RunStep executes a single YCSB transaction operation.
func (s *YcsbBenchmarkState) RunStep(ctx context.Context, client *spanner.Client) (Operation, time.Duration, error) {
	op := ChooseOperation(s.workload)
	start := time.Now()
	var err error

	switch op {
	case OperationRead:
		err = s.executeRead(ctx, client)
	case OperationUpdate:
		err = s.executeUpdate(ctx, client)
	case OperationInsert:
		err = s.executeInsert(ctx, client)
	case OperationScan:
		err = s.executeScan(ctx, client)
	case OperationReadModifyWrite:
		err = s.executeReadModifyWrite(ctx, client)
	}

	duration := time.Since(start)
	return op, duration, err
}

func (s *YcsbBenchmarkState) executeRead(ctx context.Context, client *spanner.Client) error {
	start := time.Now()
	var key string
	if s.workload == WorkloadD && !s.isMock {
		key = s.skewedLatestGenerator.NextKey(s.zeroPadding)
	} else {
		key = s.getRandomKey()
	}

	var err error
	if s.useReadRow {
		row, readErr := client.Single().ReadRow(ctx, s.tableName, spanner.Key{key}, s.fieldNames)
		if readErr != nil {
			err = readErr
		} else {
			consumeRow(row, s.fieldCount)
		}
	} else {
		stmt := spanner.Statement{
			SQL:    s.readSQL,
			Params: map[string]interface{}{"id": key},
		}
		iter := client.Single().Query(ctx, stmt)
		defer iter.Stop()

		found := false
		for {
			row, iterErr := iter.Next()
			if iterErr == iterator.Done {
				break
			}
			if iterErr != nil {
				err = iterErr
				break
			}
			consumeRow(row, s.fieldCount)
			found = true
		}
		if err == nil && !found {
			err = fmt.Errorf("row not found for key: %s", key)
		}
	}

	durationNs := uint64(time.Since(start).Nanoseconds())
	s.readTotalDurationNs.Add(durationNs)
	s.readOperationCount.Add(1)
	return err
}

func (s *YcsbBenchmarkState) executeUpdate(ctx context.Context, client *spanner.Client) error {
	start := time.Now()
	key := s.getRandomKey()
	fieldIndex := rand.IntN(s.fieldCount)
	fieldName := s.fieldNames[fieldIndex]
	value := GenerateRandomString(s.fieldLength)

	mutation := spanner.InsertOrUpdate(s.tableName, []string{"id", fieldName}, []interface{}{key, value})
	_, err := client.Apply(ctx, []*spanner.Mutation{mutation}, spanner.ApplyAtLeastOnce())

	durationNs := uint64(time.Since(start).Nanoseconds())
	s.updateTotalDurationNs.Add(durationNs)
	s.updateOperationCount.Add(1)
	return err
}

func (s *YcsbBenchmarkState) executeInsert(ctx context.Context, client *spanner.Client) error {
	start := time.Now()
	var recordNumber int64
	if s.isMock {
		recordNumber = 0
	} else {
		recordNumber = s.insertKeySequence.Add(1) - 1
	}
	key := BuildKeyName(recordNumber, s.zeroPadding)

	colValues := make([]interface{}, 0, s.fieldCount+1)
	colValues = append(colValues, key)

	for i := 0; i < s.fieldCount; i++ {
		colValues = append(colValues, GenerateRandomString(s.fieldLength))
	}

	mutation := spanner.InsertOrUpdate(s.tableName, s.insertColNames, colValues)
	_, err := client.Apply(ctx, []*spanner.Mutation{mutation}, spanner.ApplyAtLeastOnce())

	durationNs := uint64(time.Since(start).Nanoseconds())
	s.insertTotalDurationNs.Add(durationNs)
	s.insertOperationCount.Add(1)
	return err
}

func (s *YcsbBenchmarkState) executeScan(ctx context.Context, client *spanner.Client) error {
	start := time.Now()
	startKey := s.getRandomKey()
	scanLength := int64(10)
	if !s.isMock {
		scanLength = int64(rand.IntN(100) + 1)
	}

	var err error
	if s.useReadRow {
		iter := client.Single().ReadWithOptions(ctx, s.tableName, spanner.KeyRange{
			Start: spanner.Key{startKey},
			Kind:  spanner.ClosedOpen,
		}, s.fieldNames, &spanner.ReadOptions{Limit: int(scanLength)})
		defer iter.Stop()

		for {
			row, iterErr := iter.Next()
			if iterErr == iterator.Done {
				break
			}
			if iterErr != nil {
				err = iterErr
				break
			}
			consumeRow(row, s.fieldCount)
		}
	} else {
		stmt := spanner.Statement{
			SQL: s.scanSQL,
			Params: map[string]interface{}{
				"startKey":   startKey,
				"scanLength": scanLength,
			},
		}
		iter := client.Single().Query(ctx, stmt)
		defer iter.Stop()

		for {
			row, iterErr := iter.Next()
			if iterErr == iterator.Done {
				break
			}
			if iterErr != nil {
				err = iterErr
				break
			}
			consumeRow(row, s.fieldCount)
		}
	}

	durationNs := uint64(time.Since(start).Nanoseconds())
	s.scanTotalDurationNs.Add(durationNs)
	s.scanOperationCount.Add(1)
	return err
}

func (s *YcsbBenchmarkState) executeReadModifyWrite(ctx context.Context, client *spanner.Client) error {
	start := time.Now()
	key := s.getRandomKey()
	fieldIndex := rand.IntN(s.fieldCount)
	fieldName := s.fieldNames[fieldIndex]
	value := GenerateRandomString(s.fieldLength)

	_, err := client.ReadWriteTransaction(ctx, func(ctx context.Context, tx *spanner.ReadWriteTransaction) error {
		row, readErr := tx.ReadRow(ctx, s.tableName, spanner.Key{key}, s.fieldNames)
		if readErr != nil {
			return readErr
		}
		consumeRow(row, s.fieldCount)

		mutation := spanner.InsertOrUpdate(s.tableName, []string{"id", fieldName}, []interface{}{key, value})
		return tx.BufferWrite([]*spanner.Mutation{mutation})
	})

	durationNs := uint64(time.Since(start).Nanoseconds())
	s.rmwTotalDurationNs.Add(durationNs)
	s.rmwOperationCount.Add(1)
	return err
}

var blackhole atomic.Int64

func consumeRow(row *spanner.Row, fieldCount int) {
	if row == nil {
		return
	}
	sum := 0
	for i := 0; i < fieldCount; i++ {
		var val string
		if err := row.Column(i, &val); err == nil {
			sum += len(val)
		}
	}
	if sum > 0 {
		blackhole.Add(int64(sum))
	}
}

// PrintSummary prints latency and operation summary statistics across all workload operations.
func (s *YcsbBenchmarkState) PrintSummary() {
	reads := s.readOperationCount.Load()
	updates := s.updateOperationCount.Load()
	inserts := s.insertOperationCount.Load()
	scans := s.scanOperationCount.Load()
	rmws := s.rmwOperationCount.Load()

	if reads > 0 {
		avgReadMs := (float64(s.readTotalDurationNs.Load()) / 1000000.0) / float64(reads)
		fmt.Printf("  [READ]   Count: %d ops, Avg Latency: %.2f ms\n", reads, avgReadMs)
	}
	if updates > 0 {
		avgUpdateMs := (float64(s.updateTotalDurationNs.Load()) / 1000000.0) / float64(updates)
		fmt.Printf("  [UPDATE] Count: %d ops, Avg Latency: %.2f ms\n", updates, avgUpdateMs)
	}
	if inserts > 0 {
		avgInsertMs := (float64(s.insertTotalDurationNs.Load()) / 1000000.0) / float64(inserts)
		fmt.Printf("  [INSERT] Count: %d ops, Avg Latency: %.2f ms\n", inserts, avgInsertMs)
	}
	if scans > 0 {
		avgScanMs := (float64(s.scanTotalDurationNs.Load()) / 1000000.0) / float64(scans)
		fmt.Printf("  [SCAN]   Count: %d ops, Avg Latency: %.2f ms\n", scans, avgScanMs)
	}
	if rmws > 0 {
		avgRmwMs := (float64(s.rmwTotalDurationNs.Load()) / 1000000.0) / float64(rmws)
		fmt.Printf("  [RMW]    Count: %d ops, Avg Latency: %.2f ms\n", rmws, avgRmwMs)
	}
}
