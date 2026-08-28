package com.google.cloud.spanner.benchmark.ycsb;

import java.util.concurrent.ThreadLocalRandom;

/** Standard YCSB workload profiles. */
public enum YcsbWorkload {
  A(0.50, 0.50, 0.0, 0.0, 0.0),
  B(0.95, 0.05, 0.0, 0.0, 0.0),
  C(1.00, 0.00, 0.0, 0.0, 0.0),
  D(0.95, 0.00, 0.05, 0.0, 0.0),
  E(0.00, 0.00, 0.05, 0.95, 0.0),
  F(0.50, 0.00, 0.0, 0.0, 0.50);

  public enum Operation {
    READ,
    UPDATE,
    INSERT,
    SCAN,
    READ_MODIFY_WRITE
  }

  private final double readProportion;
  private final double updateProportion;
  private final double insertProportion;
  private final double scanProportion;
  private final double readModifyWriteProportion;

  YcsbWorkload(
      double readProportion,
      double updateProportion,
      double insertProportion,
      double scanProportion,
      double readModifyWriteProportion) {
    this.readProportion = readProportion;
    this.updateProportion = updateProportion;
    this.insertProportion = insertProportion;
    this.scanProportion = scanProportion;
    this.readModifyWriteProportion = readModifyWriteProportion;
  }

  public Operation nextOperation() {
    double randomValue = ThreadLocalRandom.current().nextDouble();
    double cumulative = readProportion;
    if (randomValue < cumulative) {
      return Operation.READ;
    }
    cumulative += updateProportion;
    if (randomValue < cumulative) {
      return Operation.UPDATE;
    }
    cumulative += insertProportion;
    if (randomValue < cumulative) {
      return Operation.INSERT;
    }
    cumulative += scanProportion;
    if (randomValue < cumulative) {
      return Operation.SCAN;
    }
    return Operation.READ_MODIFY_WRITE;
  }

  public double getReadProportion() {
    return readProportion;
  }

  public double getUpdateProportion() {
    return updateProportion;
  }

  public double getInsertProportion() {
    return insertProportion;
  }

  public double getScanProportion() {
    return scanProportion;
  }

  public double getReadModifyWriteProportion() {
    return readModifyWriteProportion;
  }
}
