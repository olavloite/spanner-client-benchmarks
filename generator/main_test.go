package main

import (
	"flag"
	"os"
	"testing"
	"time"
)

func TestParseFlagsDefaults(t *testing.T) {
	// Save and restore os.Args
	oldArgs := os.Args
	defer func() { os.Args = oldArgs }()

	// Reset default FlagSet to test defaults
	flag.CommandLine = flag.NewFlagSet(os.Args[0], flag.ExitOnError)

	os.Args = []string{"cmd"}
	cfg := parseFlags()

	if cfg.SocketPath != "/tmp/benchmark.sock" {
		t.Errorf("Expected default SocketPath /tmp/benchmark.sock, got %s", cfg.SocketPath)
	}
	if cfg.LoadType != LoadTypeSteady {
		t.Errorf("Expected default LoadType steady, got %s", cfg.LoadType)
	}
	if cfg.TargetTPS != 10.0 {
		t.Errorf("Expected default TargetTPS 10.0, got %f", cfg.TargetTPS)
	}
	if cfg.Duration != 1*time.Minute {
		t.Errorf("Expected default Duration 1m, got %v", cfg.Duration)
	}
}

func TestParseFlagsOverrides(t *testing.T) {
	// Save and restore os.Args
	oldArgs := os.Args
	defer func() { os.Args = oldArgs }()

	// Reset default FlagSet to test overrides
	flag.CommandLine = flag.NewFlagSet(os.Args[0], flag.ExitOnError)

	os.Args = []string{
		"cmd",
		"--socket-path=/var/run/custom.sock",
		"--load-type=spiky",
		"--tps=250.5",
		"--duration=10s",
		"--cycle-duration=30m",
		"--peak-factor=3.5",
		"--burst-factor=2.0",
		"--burst-duration=5s",
		"--burst-fraction=0.25",
	}
	cfg := parseFlags()

	if cfg.SocketPath != "/var/run/custom.sock" {
		t.Errorf("Expected SocketPath /var/run/custom.sock, got %s", cfg.SocketPath)
	}
	if cfg.LoadType != LoadTypeSpiky {
		t.Errorf("Expected LoadType spiky, got %s", cfg.LoadType)
	}
	if cfg.TargetTPS != 250.5 {
		t.Errorf("Expected TargetTPS 250.5, got %f", cfg.TargetTPS)
	}
	if cfg.Duration != 10*time.Second {
		t.Errorf("Expected Duration 10s, got %v", cfg.Duration)
	}
	if cfg.CycleDuration != 30*time.Minute {
		t.Errorf("Expected CycleDuration 30m, got %v", cfg.CycleDuration)
	}
	if cfg.PeakFactor != 3.5 {
		t.Errorf("Expected PeakFactor 3.5, got %f", cfg.PeakFactor)
	}
	if cfg.BurstFactor != 2.0 {
		t.Errorf("Expected BurstFactor 2.0, got %f", cfg.BurstFactor)
	}
	if cfg.BurstDuration != 5*time.Second {
		t.Errorf("Expected BurstDuration 5s, got %v", cfg.BurstDuration)
	}
	if cfg.BurstFraction != 0.25 {
		t.Errorf("Expected BurstFraction 0.25, got %f", cfg.BurstFraction)
	}
}

func TestCleanupSocket(t *testing.T) {
	tmpFile, err := os.CreateTemp("", "test-cleanup-socket")
	if err != nil {
		t.Fatalf("Failed to create temp file: %v", err)
	}
	path := tmpFile.Name()
	tmpFile.Close()

	// Ensure file exists
	if _, err := os.Stat(path); os.IsNotExist(err) {
		t.Fatalf("Expected file to exist: %s", path)
	}

	cleanupSocket(path)

	// Ensure file is deleted
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Errorf("Expected file to be deleted: %s", path)
	}
}
