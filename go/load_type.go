package main

import (
	"context"
	"fmt"
	"log"
	"math"
	"math/rand"
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
	generatorTicker := time.NewTicker(1 * time.Microsecond)
	defer generatorTicker.Stop()

	for {
		select {
		case <-ctx.Done():
			close(tasks)
			wg.Wait()
			return
		case <-generatorTicker.C:
			select {
			case tasks <- struct{}{}:
			default:
				log.Printf("Task dropped: workload queue is full (1M tasks)")
			}
			time.Sleep(CalculatePoissonDelay(targetTPS))
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

	for {
		select {
		case <-ctx.Done():
			close(tasks)
			wg.Wait()
			return
		default:
			now := time.Now()
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

			var delay time.Duration
			if currentRate <= 0.0 {
				delay = 1 * time.Hour
			} else {
				delay = CalculatePoissonDelay(currentRate)
			}

			timeToStateChange := nextStateChangeTime.Sub(now)
			if delay > timeToStateChange {
				time.Sleep(timeToStateChange)
				continue
			}

			select {
			case tasks <- struct{}{}:
			default:
				log.Printf("Task dropped: workload queue is full (1M tasks)")
			}

			time.Sleep(delay)
		}
	}
}

type GradualGenerator struct{}

func (g *GradualGenerator) Run(ctx context.Context, b Benchmark, client *spanner.Client, latencyHistogram metric.Float64Histogram, operationCounter metric.Int64Counter, errorCounter metric.Int64Counter, tableName string, targetTPS float64, concurrentThreads int, minId, maxId int64, attributes metric.MeasurementOption, cycleDurationStr string, peakFactor, burstFactor, burstDuration, burstFraction float64, tasks chan struct{}, wg *sync.WaitGroup) {
	cycleDuration := ParseDuration(cycleDurationStr)
	cycleDurationNs := float64(cycleDuration.Nanoseconds())
	amplitude := targetTPS * (peakFactor - 1.0)
	startTime := time.Now()

	for {
		select {
		case <-ctx.Done():
			close(tasks)
			wg.Wait()
			return
		default:
			now := time.Now()
			elapsedNs := float64(now.Sub(startTime).Nanoseconds())

			// Calculate rate based on sine wave
			angle := (2.0 * math.Pi * math.Mod(elapsedNs, cycleDurationNs)) / cycleDurationNs
			currentRate := targetTPS + amplitude*math.Cos(angle-math.Pi)

			select {
			case tasks <- struct{}{}:
			default:
				log.Printf("Task dropped: workload queue is full (1M tasks)")
			}

			time.Sleep(CalculatePoissonDelay(currentRate))
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
