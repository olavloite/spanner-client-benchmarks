package main

import (
	"flag"
	"log"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"
)

func main() {
	cfg := parseFlags()

	// Handle SIGINT/SIGTERM to cleanly clean up socket file
	sigs := make(chan os.Signal, 1)
	signal.Notify(sigs, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigs
		log.Println("Received termination signal, cleaning up socket and exiting...")
		cleanupSocket(cfg.SocketPath)
		os.Exit(0)
	}()

	// Always cleanup socket before binding
	cleanupSocket(cfg.SocketPath)

	// Create directory for socket if it doesn't exist
	dir := filepath.Dir(cfg.SocketPath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		log.Fatalf("Failed to create socket directory: %v", err)
	}

	listener, err := net.Listen("unix", cfg.SocketPath)
	if err != nil {
		log.Fatalf("Failed to listen on unix socket %s: %v", cfg.SocketPath, err)
	}
	defer listener.Close()
	defer cleanupSocket(cfg.SocketPath)

	log.Printf("Workload generator listening on Unix socket: %s", cfg.SocketPath)

	for {
		conn, err := listener.Accept()
		if err != nil {
			log.Printf("Accept connection error: %v", err)
			continue
		}
		log.Println("Benchmark client connected. Waiting for READY signal...")
		handleClient(conn, cfg)
	}
}

func parseFlags() Config {
	socketPath := flag.String("socket-path", "/tmp/benchmark.sock", "Path to Unix Domain Socket")
	loadTypeStr := flag.String("load-type", "steady", "Load type: steady, spiky, gradual")
	tps := flag.Float64("tps", 10.0, "Target transactions per second (TPS)")
	durationStr := flag.String("duration", "1m", "Benchmark duration (e.g. 30s, 5m, 1h)")
	cycleDurationStr := flag.String("cycle-duration", "1h", "Gradual load cycle duration")
	peakFactor := flag.Float64("peak-factor", 2.0, "Gradual load peak factor")
	burstFactor := flag.Float64("burst-factor", 1.0, "Spiky load burst factor")
	burstDurationStr := flag.String("burst-duration", "1s", "Spiky load burst duration")
	burstFraction := flag.Float64("burst-fraction", 0.1, "Spiky load burst fraction")

	flag.Parse()

	duration, err := time.ParseDuration(*durationStr)
	if err != nil {
		log.Fatalf("Invalid duration: %v", err)
	}

	cycleDuration, err := time.ParseDuration(*cycleDurationStr)
	if err != nil {
		log.Fatalf("Invalid cycle-duration: %v", err)
	}

	burstDuration, err := time.ParseDuration(*burstDurationStr)
	if err != nil {
		log.Fatalf("Invalid burst-duration: %v", err)
	}

	return Config{
		SocketPath:    *socketPath,
		LoadType:      LoadType(*loadTypeStr),
		TargetTPS:     *tps,
		Duration:      duration,
		CycleDuration: cycleDuration,
		PeakFactor:    *peakFactor,
		BurstFactor:   *burstFactor,
		BurstDuration: burstDuration,
		BurstFraction: *burstFraction,
	}
}

func cleanupSocket(path string) {
	if _, err := os.Stat(path); err == nil {
		if err := os.Remove(path); err != nil {
			log.Printf("Warning: failed to remove socket file: %v", err)
		}
	}
}
