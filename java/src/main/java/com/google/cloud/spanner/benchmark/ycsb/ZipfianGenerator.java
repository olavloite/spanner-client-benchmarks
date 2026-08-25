package com.google.cloud.spanner.benchmark.ycsb;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A generator of a Zipfian distribution, based on the algorithm by Jim Gray et al. ("Quickly
 * Generating Billion-Record Synthetic Databases", SIGMOD 1994).
 */
public class ZipfianGenerator {

  public static final double DEFAULT_ZIPFIAN_CONSTANT = 0.99;

  // Precomputed zeta values for theta = 0.99 to eliminate expensive loops on the hot path
  public static final double ZETAN_1K = 7.728953217284738;
  public static final double ZETAN_100K = 12.778338062551171;

  private final long items;
  private final long base;
  private final double zipfianConstant;
  private final double alpha;
  private final double zetan;
  private final double eta;
  private final double zeta2Theta;

  public ZipfianGenerator(long items) {
    this(0, items - 1, DEFAULT_ZIPFIAN_CONSTANT);
  }

  public ZipfianGenerator(long min, long max) {
    this(min, max, DEFAULT_ZIPFIAN_CONSTANT);
  }

  public ZipfianGenerator(long min, long max, double zipfianConstant) {
    this.items = max - min + 1;
    this.base = min;
    this.zipfianConstant = zipfianConstant;
    this.zeta2Theta = zeta(2, zipfianConstant);
    this.alpha = 1.0 / (1.0 - zipfianConstant);
    this.zetan = computeZeta(this.items, zipfianConstant);
    this.eta = (1.0 - Math.pow(2.0 / items, 1.0 - zipfianConstant)) / (1.0 - zeta2Theta / zetan);
  }

  public ZipfianGenerator(long min, long max, double zipfianConstant, double zetan) {
    this.items = max - min + 1;
    this.base = min;
    this.zipfianConstant = zipfianConstant;
    this.zeta2Theta = zeta(2, zipfianConstant);
    this.alpha = 1.0 / (1.0 - zipfianConstant);
    this.zetan = zetan;
    this.eta = (1.0 - Math.pow(2.0 / items, 1.0 - zipfianConstant)) / (1.0 - zeta2Theta / zetan);
  }

  public long nextLong() {
    double u = ThreadLocalRandom.current().nextDouble();
    double uz = u * zetan;

    if (uz < 1.0) {
      return base;
    }
    if (uz < 1.0 + Math.pow(0.5, zipfianConstant)) {
      return base + 1;
    }

    long result = base + (long) (items * Math.pow(eta * u - eta + 1.0, alpha));
    if (result > base + items - 1) {
      result = base + items - 1;
    }
    return result;
  }

  /**
   * Generates a Zipfian value in the range [0, itemCount - 1] for a dynamic itemCount in O(1) time.
   */
  public long nextLong(long itemCount) {
    if (itemCount <= 1) {
      return 0;
    }
    double u = ThreadLocalRandom.current().nextDouble();
    double z = computeZeta(itemCount, zipfianConstant);
    double uz = u * z;

    if (uz < 1.0) {
      return 0;
    }
    if (uz < 1.0 + Math.pow(0.5, zipfianConstant)) {
      return 1;
    }

    double localEta =
        (1.0 - Math.pow(2.0 / itemCount, 1.0 - zipfianConstant)) / (1.0 - zeta2Theta / z);
    long result = (long) (itemCount * Math.pow(localEta * u - localEta + 1.0, alpha));
    if (result > itemCount - 1) {
      result = itemCount - 1;
    }
    return result;
  }

  public String nextKey(int zeroPadding) {
    return buildKeyName(nextLong(), zeroPadding);
  }

  public static String buildKeyName(long keyNumber, int zeroPadding) {
    String value = Long.toString(keyNumber);
    int fill = zeroPadding - value.length();
    if (fill <= 0) {
      return "user" + value;
    }
    StringBuilder builder = new StringBuilder("user");
    for (int i = 0; i < fill; i++) {
      builder.append('0');
    }
    builder.append(value);
    return builder.toString();
  }

  public long getItems() {
    return items;
  }

  public long getBase() {
    return base;
  }

  public double getZipfianConstant() {
    return zipfianConstant;
  }

  public double getZetan() {
    return zetan;
  }

  public double getZeta2Theta() {
    return zeta2Theta;
  }

  private static double zeta(long n, double theta) {
    double sum = 0.0;
    for (long i = 0; i < n; i++) {
      sum += 1.0 / Math.pow(i + 1, theta);
    }
    return sum;
  }

  /**
   * Computes or approximates the zeta function for n items in O(1) time using precomputed constants
   * for standard theta = 0.99 (Jim Gray et al., SIGMOD '94).
   */
  public static double computeZeta(long n, double theta) {
    if (theta == DEFAULT_ZIPFIAN_CONSTANT) {
      if (n >= 1_000L) {
        return ZETAN_1K
            + (Math.pow(n, 1.0 - theta) - Math.pow(1_000L, 1.0 - theta)) / (1.0 - theta);
      }
      return zeta(n, theta);
    }

    long n0 = Math.min(n, 1000L);
    double sum = zeta(n0, theta);
    if (n > n0) {
      sum += (Math.pow(n, 1.0 - theta) - Math.pow(n0, 1.0 - theta)) / (1.0 - theta);
    }
    return sum;
  }
}
