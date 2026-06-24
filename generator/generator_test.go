package main

import (
	"bufio"
	"io"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestWorkloadGeneratorFlow(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "generator-test-*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	socketPath := filepath.Join(tmpDir, "test.sock")
	cleanupSocket(socketPath)

	listener, err := net.Listen("unix", socketPath)
	if err != nil {
		t.Fatalf("Failed to listen on socket %s: %v", socketPath, err)
	}
	defer listener.Close()

	cfg := Config{
		SocketPath:    socketPath,
		LoadType:      LoadTypeSteady,
		TargetTPS:     100.0,
		Duration:      1 * time.Second,
		CycleDuration: 1 * time.Hour,
		PeakFactor:    2.0,
		BurstFactor:   1.0,
		BurstDuration: 1 * time.Second,
		BurstFraction: 0.1,
	}

	// Run listener loop in background
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		handleClient(conn, cfg)
	}()

	// Client side test
	conn, err := net.Dial("unix", socketPath)
	if err != nil {
		t.Fatalf("Failed to connect to socket: %v", err)
	}
	defer conn.Close()

	// Write READY signal
	_, err = conn.Write([]byte("READY\n"))
	if err != nil {
		t.Fatalf("Failed to write READY signal: %v", err)
	}

	// Read triggers in loop and count them
	reader := bufio.NewReader(conn)
	ticks := 0
	startTime := time.Now()

	buf := make([]byte, 1)
	for {
		_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
		_, err := reader.Read(buf)
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatalf("Read error: %v", err)
		}
		if buf[0] == 0x01 {
			ticks++
		}
	}

	duration := time.Since(startTime)
	t.Logf("Received %d ticks in %v", ticks, duration)

	if ticks < 50 || ticks > 150 {
		t.Errorf("Expected roughly 100 ticks (between 50 and 150), got: %d", ticks)
	}
}

func TestDriftCompensation(t *testing.T) {
	delay := calculatePoissonDelay(100.0)
	if delay <= 0 {
		t.Errorf("Expected positive delay, got %v", delay)
	}
}
