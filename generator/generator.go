package main

import (
	"bufio"
	"context"
	"log"
	"math"
	"math/rand"
	"net"
	"sync"
	"time"
)

type LoadType string

const (
	LoadTypeSteady  LoadType = "steady"
	LoadTypeSpiky   LoadType = "spiky"
	LoadTypeGradual LoadType = "gradual"
)

type Config struct {
	SocketPath    string
	LoadType      LoadType
	TargetTPS     float64
	Duration      time.Duration
	CycleDuration time.Duration
	PeakFactor    float64
	BurstFactor   float64
	BurstDuration time.Duration
	BurstFraction float64
}

func handleClient(conn net.Conn, cfg Config) {
	defer conn.Close()

	// Wait for READY signal from client (ends with newline)
	reader := bufio.NewReader(conn)
	signalStr, err := reader.ReadString('\n')
	if err != nil {
		log.Printf("Failed to read readiness from client: %v", err)
		return
	}

	if signalStr != "READY\n" {
		log.Printf("Received invalid readiness signal: %q", signalStr)
		return
	}

	log.Println("READY signal received. Starting Poisson timeline generation...")

	ctx, cancel := context.WithTimeout(context.Background(), cfg.Duration)
	defer cancel()

	// Seed random generator
	rand.Seed(time.Now().UnixNano())

	startTime := time.Now()

	// Calculate number of dynamic workers to ensure sleeps are >= 5ms (Max 200 TPS per worker)
	numWorkers := int(math.Ceil(cfg.TargetTPS / 200.0))
	if numWorkers < 1 {
		numWorkers = 1
	}

	log.Printf("Target TPS: %.2f. Spawning %d scaled generator workers.", cfg.TargetTPS, numWorkers)

	wg := &sync.WaitGroup{}
	wg.Add(numWorkers)

	for i := 0; i < numWorkers; i++ {
		// Stagger worker starts slightly to distribute their Poisson processes
		time.Sleep(time.Duration(rand.Intn(10)) * time.Millisecond)
		go runGeneratorWorker(ctx, cfg, numWorkers, startTime, conn, wg)
	}

	// Wait for benchmark duration to complete, or client disconnect
	wg.Wait()
	log.Println("Poisson timeline generation complete. Connection closed.")
}

func runGeneratorWorker(ctx context.Context, cfg Config, numWorkers int, startTime time.Time, conn net.Conn, wg *sync.WaitGroup) {
	defer wg.Done()

	nextEventTime := time.Now()

	// Pre-calculate Spiky rates if needed
	var mu1, mu2 float64
	var spikyState struct {
		sync.Mutex
		inBurst             bool
		nextStateChangeTime time.Time
	}

	if cfg.LoadType == LoadTypeSpiky {
		mu2 = 1.0 / cfg.BurstDuration.Seconds()
		mu1 = mu2 * cfg.BurstFraction / (1.0 - cfg.BurstFraction)

		spikyState.inBurst = false
		spikyState.nextStateChangeTime = startTime.Add(calculatePoissonDelay(mu1))
	}

	for {
		select {
		case <-ctx.Done():
			return
		default:
			// Calculate current target rate for this worker
			var targetRate float64
			now := time.Now()

			switch cfg.LoadType {
			case LoadTypeSteady:
				targetRate = cfg.TargetTPS
			case LoadTypeSpiky:
				spikyState.Lock()
				if now.After(spikyState.nextStateChangeTime) {
					spikyState.inBurst = !spikyState.inBurst
					var delay time.Duration
					if spikyState.inBurst {
						delay = calculatePoissonDelay(mu2)
					} else {
						delay = calculatePoissonDelay(mu1)
					}
					spikyState.nextStateChangeTime = now.Add(delay)
				}

				rBurst := cfg.TargetTPS * cfg.BurstFactor
				rNormal := (cfg.TargetTPS - cfg.BurstFraction*rBurst) / (1.0 - cfg.BurstFraction)
				if spikyState.inBurst {
					targetRate = rBurst
				} else {
					targetRate = rNormal
				}
				spikyState.Unlock()

			case LoadTypeGradual:
				cycleNs := float64(cfg.CycleDuration.Nanoseconds())
				elapsedNs := float64(now.Sub(startTime).Nanoseconds())
				amplitude := cfg.TargetTPS * (cfg.PeakFactor - 1.0)
				angle := (2.0 * math.Pi * math.Mod(elapsedNs, cycleNs)) / cycleNs
				targetRate = cfg.TargetTPS + amplitude*math.Cos(angle-math.Pi)
			}

			// Distribute rate equally among workers
			workerRate := targetRate / float64(numWorkers)
			if workerRate <= 0.0 {
				workerRate = 0.0001 // Prevent divide-by-zero or negative delays
			}

			delay := calculatePoissonDelay(workerRate)
			nextEventTime = nextEventTime.Add(delay)

			now = time.Now()
			if nextEventTime.After(now) {
				time.Sleep(nextEventTime.Sub(now))
			} else {
				// Fall behind safety check: snap timeline if lagged by more than 100ms
				if now.Sub(nextEventTime) > 100*time.Millisecond {
					nextEventTime = now
				}
			}

			// Write trigger byte (0x01) to socket
			_, err := conn.Write([]byte{0x01})
			if err != nil {
				// Client disconnected
				return
			}
		}
	}
}

func calculatePoissonDelay(rate float64) time.Duration {
	u := rand.Float64()
	safeU := u
	if safeU == 1.0 {
		safeU = 0.999999999
	}
	delaySeconds := -math.Log(1.0-safeU) / rate
	return time.Duration(delaySeconds * float64(time.Second))
}
