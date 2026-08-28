package com.google.cloud.spanner.benchmark.ycsb;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates keys with a Zipfian skew towards the most recently inserted records. Used in YCSB
 * Workload D ("Read Latest").
 *
 * <p>Caches zeta and eta parameters across reads since the basis sequence only increments on insert
 * operations (5% of workload D operations), eliminating expensive Math.pow calculations on 95% of
 * operations.
 */
public class SkewedLatestGenerator {

  private final AtomicLong basis;
  private final double zipfianConstant;
  private final double zeta2Theta;
  private final double alpha;
  private volatile ZipfianParams cachedParams;

  private static final class ZipfianParams {
    final long itemCount;
    final double zetan;
    final double eta;

    ZipfianParams(long itemCount, double zipfianConstant, double zeta2Theta) {
      this.itemCount = itemCount;
      this.zetan = ZipfianGenerator.computeZeta(itemCount, zipfianConstant);
      this.eta =
          (1.0 - Math.pow(2.0 / itemCount, 1.0 - zipfianConstant))
              / (1.0 - zeta2Theta / this.zetan);
    }
  }

  public SkewedLatestGenerator(AtomicLong basis) {
    this(basis, ZipfianGenerator.DEFAULT_ZIPFIAN_CONSTANT);
  }

  public SkewedLatestGenerator(AtomicLong basis, double zipfianConstant) {
    this.basis = basis;
    this.zipfianConstant = zipfianConstant;
    this.zeta2Theta = 1.0 + Math.pow(0.5, zipfianConstant);
    this.alpha = 1.0 / (1.0 - zipfianConstant);
    long initial = basis.get();
    this.cachedParams = new ZipfianParams(Math.max(2, initial), zipfianConstant, this.zeta2Theta);
  }

  public long nextLong() {
    long max = basis.get();
    if (max <= 1) {
      return 0;
    }

    ZipfianParams params = this.cachedParams;
    if (params == null || params.itemCount != max) {
      params = new ZipfianParams(max, zipfianConstant, zeta2Theta);
      this.cachedParams = params;
    }

    double u = ThreadLocalRandom.current().nextDouble();
    double uz = u * params.zetan;

    if (uz < 1.0) {
      return max - 1;
    }
    if (uz < 1.0 + Math.pow(0.5, zipfianConstant)) {
      return Math.max(0, max - 2);
    }

    long offset = (long) (max * Math.pow(params.eta * u - params.eta + 1.0, alpha));
    long key = max - 1 - offset;
    return Math.max(0, key);
  }

  public String nextKey(int zeroPadding) {
    return ZipfianGenerator.buildKeyName(nextLong(), zeroPadding);
  }
}
