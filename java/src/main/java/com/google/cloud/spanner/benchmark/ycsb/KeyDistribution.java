package com.google.cloud.spanner.benchmark.ycsb;

/** Key distribution strategies for YCSB benchmarks. */
public enum KeyDistribution {
  ZIPFIAN,
  SCRAMBLED_ZIPFIAN,
  UNIFORM
}
