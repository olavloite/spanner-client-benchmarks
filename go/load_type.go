package main

import (
	"context"
	"fmt"
	"log"
	"math"
	"math/rand"
	"runtime"
	"strings"
	"sync"
	"time"

	"cloud.google.com/go/spanner"
	"github.com/urfave/cli/v3"
	"go.opentelemetry.io/otel/metric"
)

type LoadType int

const (
	LoadTypeSteady LoadType = iota
	LoadTypeSpiky
	LoadTypeGradual
)

func (l LoadType) String() string {
	switch l {
	case LoadTypeSteady:
		return "steady"
	case LoadTypeSpiky:
		return "spiky"
	case LoadTypeGradual:
		return "gradual"
	default:
		return "unknown"
	}
}

func ParseLoadType(s string) (LoadType, error) {
	switch strings.ToLower(s) {
	case "steady":
		return LoadTypeSteady, nil
	case "spiky":
		return LoadTypeSpiky, nil
	case "gradual":
		return LoadTypeGradual, nil
	default:
		return LoadTypeSteady, fmt.Errorf("unknown load type: %s", s)
	}
}

type LoadGenerator interface {
	Run(ctx context.Context, b Benchmark, client *spanner.Client, latencyHistogram metric.Float64Histogram, operationCounter metric.Int64Counter, errorCounter metric.Int64Counter, tableName string, targetTPS float64, concurrentThreads int, minId, maxId int64, attributes metric.MeasurementOption, cycleDurationStr string, peakFactor, burstFactor, burstDuration, burstFraction float64, tasks chan struct{}, wg *sync.WaitGroup)
}

var generators = map[LoadType]LoadGenerator{
	LoadTypeSteady:  &SteadyGenerator{},
	LoadTypeSpiky:   &SpikyGenerator{},
	LoadTypeGradual: &GradualGenerator{},
}

type SteadyGenerator struct{}

func (g *SteadyGenerator) Run(ctx context.Context, b Benchmark, client *spanner.Client, latencyHistogram metric.Float64Histogram, operationCounter metric.Int64Counter, errorCounter metric.Int64Counter, tableName string, targetTPS float64, concurrentThreads int, minId, maxId int64, attributes metric.MeasurementOption, cycleDurationStr string, peakFactor, burstFactor, burstDuration, burstFraction float64, tasks chan struct{}, wg *sync.WaitGroup) {
	nextTickTime := time.Now()
	tickDuration := 1 * time.Millisecond
	poissonTimeline := time.Now()

	for {
		select {
		case <-ctx.Done():
			close(tasks)
			wg.Wait()
			return
		default:
			now := time.Now()
			if nextTickTime.Before(now) {
				nextTickTime = now
			}
			targetTickEnd := nextTickTime.Add(tickDuration)

			if poissonTimeline.Before(nextTickTime) {
				poissonTimeline = nextTickTime
			}

			// Calculate number of tasks for this 1ms tick
			count := 0
			for poissonTimeline.Before(targetTickEnd) {
				count++
				delay := CalculatePoissonDelay(targetTPS)
				poissonTimeline = poissonTimeline.Add(delay)
			}

			if queueSize := len(tasks); queueSize > 0 {
				logQueueSize(queueSize)
			}

			for i := 0; i < count; i++ {
				select {
				case tasks <- struct{}{}:
				default:
					log.Printf("Task dropped: workload queue is full (1M tasks)")
				}
			}

			nextTickTime = nextTickTime.Add(tickDuration)
			SleepHybrid(nextTickTime)
		}
	}
}

type SpikyGenerator struct{}

func (g *SpikyGenerator) Run(ctx context.Context, b Benchmark, client *spanner.Client, latencyHistogram metric.Float64Histogram, operationCounter metric.Int64Counter, errorCounter metric.Int64Counter, tableName string, targetTPS float64, concurrentThreads int, minId, maxId int64, attributes metric.MeasurementOption, cycleDurationStr string, peakFactor, burstFactor, burstDuration, burstFraction float64, tasks chan struct{}, wg *sync.WaitGroup) {
	rBurst := targetTPS * burstFactor
	rNormal := (targetTPS - burstFraction*rBurst) / (1.0 - burstFraction)

	mu2 := 1.0 / burstDuration
	mu1 := mu2 * burstFraction / (1.0 - burstFraction)

	inBurst := false
	nextStateChangeTime := time.Now().Add(CalculatePoissonDelay(mu1))
	nextTickTime := time.Now()
	tickDuration := 1 * time.Millisecond
	poissonTimeline := time.Now()

	for {
		select {
		case <-ctx.Done():
			close(tasks)
			wg.Wait()
			return
		default:
			now := time.Now()
			if nextTickTime.Before(now) {
				nextTickTime = now
			}
			targetTickEnd := nextTickTime.Add(tickDuration)

			if poissonTimeline.Before(nextTickTime) {
				poissonTimeline = nextTickTime
			}

			if now.After(nextStateChangeTime) {
				inBurst = !inBurst
				var nextDelay time.Duration
				if inBurst {
					nextDelay = CalculatePoissonDelay(mu2)
				} else {
					nextDelay = CalculatePoissonDelay(mu1)
				}
				nextStateChangeTime = now.Add(nextDelay)
			}

			currentRate := rNormal
			if inBurst {
				currentRate = rBurst
			}

			// Calculate number of tasks for this 1ms tick
			count := 0
			if currentRate > 0.0 {
				for poissonTimeline.Before(targetTickEnd) {
					count++
					delay := CalculatePoissonDelay(currentRate)
					poissonTimeline = poissonTimeline.Add(delay)
				}
			} else {
				poissonTimeline = targetTickEnd
			}

			if queueSize := len(tasks); queueSize > 0 {
				logQueueSize(queueSize)
			}

			for i := 0; i < count; i++ {
				select {
				case tasks <- struct{}{}:
				default:
					log.Printf("Task dropped: workload queue is full (1M tasks)")
				}
			}

			nextTickTime = nextTickTime.Add(tickDuration)
			SleepHybrid(nextTickTime)
		}
	}
}

type GradualGenerator struct{}

func (g *GradualGenerator) Run(ctx context.Context, b Benchmark, client *spanner.Client, latencyHistogram metric.Float64Histogram, operationCounter metric.Int64Counter, errorCounter metric.Int64Counter, tableName string, targetTPS float64, concurrentThreads int, minId, maxId int64, attributes metric.MeasurementOption, cycleDurationStr string, peakFactor, burstFactor, burstDuration, burstFraction float64, tasks chan struct{}, wg *sync.WaitGroup) {
	cycleDuration := ParseDuration(cycleDurationStr)
	cycleDurationNs := float64(cycleDuration.Nanoseconds())
	amplitude := targetTPS * (peakFactor - 1.0)
	startTime := time.Now()
	nextTickTime := time.Now()
	tickDuration := 1 * time.Millisecond
	poissonTimeline := time.Now()

	for {
		select {
		case <-ctx.Done():
			close(tasks)
			wg.Wait()
			return
		default:
			now := time.Now()
			if nextTickTime.Before(now) {
				nextTickTime = now
			}
			targetTickEnd := nextTickTime.Add(tickDuration)

			if poissonTimeline.Before(nextTickTime) {
				poissonTimeline = nextTickTime
			}

			elapsedNs := float64(now.Sub(startTime).Nanoseconds())
			angle := (2.0 * math.Pi * math.Mod(elapsedNs, cycleDurationNs)) / cycleDurationNs
			currentRate := targetTPS + amplitude*math.Cos(angle-math.Pi)

			// Calculate number of tasks for this 1ms tick
			count := 0
			if currentRate > 0.0 {
				for poissonTimeline.Before(targetTickEnd) {
					count++
					delay := CalculatePoissonDelay(currentRate)
					poissonTimeline = poissonTimeline.Add(delay)
				}
			} else {
				poissonTimeline = targetTickEnd
			}

			if queueSize := len(tasks); queueSize > 0 {
				logQueueSize(queueSize)
			}

			for i := 0; i < count; i++ {
				select {
				case tasks <- struct{}{}:
				default:
					log.Printf("Task dropped: workload queue is full (1M tasks)")
				}
			}

			nextTickTime = nextTickTime.Add(tickDuration)
			SleepHybrid(nextTickTime)
		}
	}
}

func ParseDuration(d string) time.Duration {
	if d == "inf" || d == "infinite" {
		return 0
	}
	duration, err := time.ParseDuration(d)
	if err != nil {
		return 0
	}
	return duration
}

func CalculatePoissonDelay(rate float64) time.Duration {
	u := rand.Float64()
	delaySeconds := -math.Log(1.0-u) / rate
	return time.Duration(delaySeconds * float64(time.Second))
}

func SleepHybrid(targetTime time.Time) {
	now := time.Now()
	if targetTime.After(now) {
		diff := targetTime.Sub(now)
		if diff > 1*time.Millisecond {
			time.Sleep(diff - 100*time.Microsecond)
		}
		for time.Now().Before(targetTime) {
			runtime.Gosched()
		}
	}
}

func ValidateAndApplyDefaults(cmd *cli.Command, loadType LoadType, cycleDurationStr *string, peakFactor, burstFactor, burstDuration, burstFraction *float64) error {
	if loadType == LoadTypeSteady {
		if cmd.IsSet("cycle-duration") || cmd.IsSet("peak-factor") || cmd.IsSet("burst-factor") || cmd.IsSet("burst-duration") || cmd.IsSet("burst-fraction") {
			return fmt.Errorf("cannot specify burst or gradual load options when load-type is steady")
		}
	} else if loadType == LoadTypeSpiky {
		if cmd.IsSet("cycle-duration") || cmd.IsSet("peak-factor") {
			return fmt.Errorf("cannot specify gradual load options when load-type is spiky")
		}
		if !cmd.IsSet("burst-factor") {
			*burstFactor = 1.0
		}
		if !cmd.IsSet("burst-duration") {
			*burstDuration = 1.0
		}
		if !cmd.IsSet("burst-fraction") {
			*burstFraction = 0.1
		}
	} else if loadType == LoadTypeGradual {
		if cmd.IsSet("burst-factor") || cmd.IsSet("burst-duration") || cmd.IsSet("burst-fraction") {
			return fmt.Errorf("cannot specify burst load options when load-type is gradual")
		}
		if !cmd.IsSet("cycle-duration") {
			*cycleDurationStr = "1h"
		}
		if !cmd.IsSet("peak-factor") {
			*peakFactor = 2.0
		}
	}
	return nil
}

var lastQueueLogTime time.Time
var queueLogMu sync.Mutex

func logQueueSize(size int) {
	queueLogMu.Lock()
	defer queueLogMu.Unlock()
	if time.Since(lastQueueLogTime) > 1*time.Second {
		log.Printf("Queue size: %d (concurrency limit reached, tasks are queueing)", size)
		lastQueueLogTime = time.Now()
	}
}
