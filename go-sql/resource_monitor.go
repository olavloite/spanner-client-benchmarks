package main

import (
	"context"
	"os"
	"runtime"
	"strconv"
	"sync"
	"syscall"
	"time"

	"go.opentelemetry.io/otel/metric"
)

var cpuLimit = func() float64 {
	limit := float64(runtime.NumCPU())
	if limitStr := os.Getenv("BENCHMARK_CPU_LIMIT"); limitStr != "" {
		if val, err := strconv.ParseFloat(limitStr, 64); err == nil && val > 0 {
			limit = val
		}
	}
	return limit
}()

type ResourceMonitor struct {
	memoryUsageHistogram    metric.Float64Histogram
	cpuUtilizationHistogram metric.Float64Histogram
	resourceProbeInterval   time.Duration
	attributes              metric.MeasurementOption
	cancel                  context.CancelFunc
	wg                      sync.WaitGroup
}

func NewResourceMonitor(memoryUsageHistogram, cpuUtilizationHistogram metric.Float64Histogram, resourceProbeIntervalStr string, attributes metric.MeasurementOption) *ResourceMonitor {
	if resourceProbeIntervalStr == "0" || resourceProbeIntervalStr == "0s" || resourceProbeIntervalStr == "" {
		return nil
	}
	interval := ParseDuration(resourceProbeIntervalStr)
	if interval <= 0 {
		return nil
	}
	return &ResourceMonitor{
		memoryUsageHistogram:    memoryUsageHistogram,
		cpuUtilizationHistogram: cpuUtilizationHistogram,
		resourceProbeInterval:   interval,
		attributes:              attributes,
	}
}

func (rm *ResourceMonitor) Start(ctx context.Context) {
	if rm == nil {
		return
	}
	monitorCtx, cancel := context.WithCancel(ctx)
	rm.cancel = cancel
	rm.wg.Add(1)
	go func() {
		defer rm.wg.Done()
		ticker := time.NewTicker(rm.resourceProbeInterval)
		defer ticker.Stop()
		var lastUtime int64
		var lastStime int64
		var lastWall time.Time
		initialized := false

		for {
			select {
			case <-monitorCtx.Done():
				return
			case now := <-ticker.C:
				rm.probeResourceUsage(monitorCtx, now, &lastUtime, &lastStime, &lastWall, &initialized)
			}
		}
	}()
}

func (rm *ResourceMonitor) Stop() {
	if rm == nil || rm.cancel == nil {
		return
	}
	rm.cancel()
	rm.wg.Wait()
}

func (rm *ResourceMonitor) probeResourceUsage(ctx context.Context, now time.Time, lastUtime *int64, lastStime *int64, lastWall *time.Time, initialized *bool) {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	rm.memoryUsageHistogram.Record(ctx, float64(m.Alloc), rm.attributes)

	var rusage syscall.Rusage
	if err := syscall.Getrusage(syscall.RUSAGE_SELF, &rusage); err == nil {
		utime := int64(rusage.Utime.Sec)*1e6 + int64(rusage.Utime.Usec)
		stime := int64(rusage.Stime.Sec)*1e6 + int64(rusage.Stime.Usec)
		if *initialized {
			elapsedWall := now.Sub(*lastWall).Seconds()
			if elapsedWall > 0 {
				cpuUtil := (float64((utime-*lastUtime)+(stime-*lastStime)) / 1e6) / elapsedWall
				rm.cpuUtilizationHistogram.Record(ctx, cpuUtil/cpuLimit, rm.attributes)
			}
		}
		*lastUtime = utime
		*lastStime = stime
		*lastWall = now
		*initialized = true
	}
}
