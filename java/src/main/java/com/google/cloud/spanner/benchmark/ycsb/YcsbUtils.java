package com.google.cloud.spanner.benchmark.ycsb;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

/** Shared utilities and fast string generation for YCSB benchmarks and data generation. */
public final class YcsbUtils {

  private static final int ASCII_POOL_SIZE = 16384;
  private static final byte[] ASCII_CHARS =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
          .getBytes(StandardCharsets.US_ASCII);
  private static final byte[] ASCII_POOL = new byte[ASCII_POOL_SIZE];

  static {
    for (int i = 0; i < ASCII_POOL_SIZE; i++) {
      ASCII_POOL[i] = ASCII_CHARS[i % ASCII_CHARS.length];
    }
  }

  private YcsbUtils() {}

  /**
   * Generates a random ASCII string of specified length. Uses an O(1) slice from the precomputed
   * pool when length <= ASCII_POOL_SIZE, or dynamically constructs the buffer if length >
   * ASCII_POOL_SIZE.
   */
  public static String generateRandomString(int length) {
    if (length <= 0) {
      return "";
    }
    if (length <= ASCII_POOL_SIZE) {
      int maxOffset = ASCII_POOL_SIZE - length;
      int offset = maxOffset > 0 ? ThreadLocalRandom.current().nextInt(maxOffset) : 0;
      return new String(ASCII_POOL, offset, length, StandardCharsets.US_ASCII);
    }
    byte[] buffer = new byte[length];
    int offset = 0;
    while (offset < length) {
      int chunkSize = Math.min(ASCII_POOL_SIZE, length - offset);
      int poolOffset = ThreadLocalRandom.current().nextInt(ASCII_POOL_SIZE - chunkSize + 1);
      System.arraycopy(ASCII_POOL, poolOffset, buffer, offset, chunkSize);
      offset += chunkSize;
    }
    return new String(buffer, StandardCharsets.US_ASCII);
  }
}
