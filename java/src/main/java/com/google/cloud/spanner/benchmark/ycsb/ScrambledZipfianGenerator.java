package com.google.cloud.spanner.benchmark.ycsb;

/**
 * A generator of a scrambled Zipfian distribution that scatters the popular items across the entire
 * key space using FNV-1a 64-bit hashing.
 */
public class ScrambledZipfianGenerator {

  public static final double USED_ZIPFIAN_CONSTANT = 0.99;
  public static final double ZETAN_10B = 26.46902820178302;
  public static final long DEFAULT_ITEM_COUNT = 10_000_000_000L;

  private final ZipfianGenerator generator;
  private final long min;
  private final long max;
  private final long itemCount;

  public ScrambledZipfianGenerator(long items) {
    this(0, items - 1);
  }

  public ScrambledZipfianGenerator(long min, long max) {
    this(min, max, USED_ZIPFIAN_CONSTANT);
  }

  public ScrambledZipfianGenerator(long min, long max, double zipfianConstant) {
    this.min = min;
    this.max = max;
    this.itemCount = max - min + 1;
    if (zipfianConstant == USED_ZIPFIAN_CONSTANT) {
      this.generator =
          new ZipfianGenerator(0, DEFAULT_ITEM_COUNT, USED_ZIPFIAN_CONSTANT, ZETAN_10B);
    } else {
      this.generator = new ZipfianGenerator(0, DEFAULT_ITEM_COUNT, zipfianConstant);
    }
  }

  public long nextLong() {
    long value = generator.nextLong();
    long hashed = fnvHash64(value);
    return min + (hashed % itemCount);
  }

  public String nextKey(int zeroPadding) {
    return ZipfianGenerator.buildKeyName(nextLong(), zeroPadding);
  }

  /**
   * 64-bit FNV-1a hash matching upstream YCSB Utils.fnvhash64 with safe handling for
   * Long.MIN_VALUE.
   */
  public static long fnvHash64(long value) {
    long hash = 0xcbf29ce484222325L;
    long fnvPrime = 0x100000001b3L;
    for (int i = 0; i < 8; i++) {
      byte octet = (byte) (value & 0xff);
      value >>>= 8;
      hash ^= (octet & 0xff);
      hash *= fnvPrime;
    }
    return hash < 0 ? (hash == Long.MIN_VALUE ? 0 : -hash) : hash;
  }
}
