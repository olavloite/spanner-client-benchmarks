package com.google.cloud.spanner.benchmark.ycsb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class YcsbBenchmarkTest {

  @Test
  public void testGenerateRandomStringLength() {
    assertEquals(0, YcsbUtils.generateRandomString(0).length());
    assertEquals(10, YcsbUtils.generateRandomString(10).length());
    assertEquals(100, YcsbUtils.generateRandomString(100).length());
    assertEquals(16384, YcsbUtils.generateRandomString(16384).length());
    assertEquals(20000, YcsbUtils.generateRandomString(20000).length());
    assertEquals(50000, YcsbUtils.generateRandomString(50000).length());
  }

  @Test
  public void testZipfianKeyFormatting() {
    assertEquals("user000000000000", ZipfianGenerator.buildKeyName(0L, 12));
    assertEquals("user000000000001", ZipfianGenerator.buildKeyName(1L, 12));
    assertEquals("user000000012345", ZipfianGenerator.buildKeyName(12345L, 12));
    assertEquals("user123456789012", ZipfianGenerator.buildKeyName(123456789012L, 12));
    assertEquals("user1234567890123", ZipfianGenerator.buildKeyName(1234567890123L, 12));
  }

  @Test
  public void testZipfianGeneratorDistribution() {
    long recordCount = 1000L;
    ZipfianGenerator generator = new ZipfianGenerator(0, recordCount - 1);
    int[] counts = new int[(int) recordCount];
    int samples = 50_000;

    for (int i = 0; i < samples; i++) {
      long value = generator.nextLong();
      assertTrue("Value must be >= 0", value >= 0);
      assertTrue("Value must be < " + recordCount, value < recordCount);
      counts[(int) value]++;
    }

    // Top 10% of items should receive significantly more hits than bottom 10%
    int top10Percent = (int) (recordCount * 0.1);
    int topHits = 0;
    for (int i = 0; i < top10Percent; i++) {
      topHits += counts[i];
    }
    int bottomHits = 0;
    for (int i = (int) (recordCount * 0.9); i < recordCount; i++) {
      bottomHits += counts[i];
    }

    assertTrue("Top 10% keys should have more samples than bottom 10%", topHits > bottomHits * 2);
    assertTrue("Most frequent key should be key 0", counts[0] > counts[(int) recordCount - 1]);
  }

  @Test
  public void testComputeZetaContinuity() {
    double theta = 0.99;
    double zeta999 = ZipfianGenerator.computeZeta(999, theta);
    double zeta1000 = ZipfianGenerator.computeZeta(1000, theta);
    double zeta1001 = ZipfianGenerator.computeZeta(1001, theta);

    assertEquals(ZipfianGenerator.ZETAN_1K, zeta1000, 1e-9);
    assertTrue("zeta(1000) must be > zeta(999)", zeta1000 > zeta999);
    assertTrue(
        "Difference between zeta(1000) and zeta(999) must be smooth (< 0.01)",
        zeta1000 - zeta999 < 0.01);
    assertTrue("zeta(1001) must be > zeta(1000)", zeta1001 > zeta1000);
    assertTrue(
        "Difference between zeta(1001) and zeta(1000) must be smooth (< 0.01)",
        zeta1001 - zeta1000 < 0.01);

    double zeta99999 = ZipfianGenerator.computeZeta(99999, theta);
    double zeta100000 = ZipfianGenerator.computeZeta(100000, theta);
    double zeta100001 = ZipfianGenerator.computeZeta(100001, theta);

    assertTrue("zeta(100000) must be > zeta(99999)", zeta100000 > zeta99999);
    assertTrue(
        "Difference between zeta(100000) and zeta(99999) must be smooth (< 0.001)",
        zeta100000 - zeta99999 < 0.001);
    assertTrue("zeta(100001) must be > zeta(100000)", zeta100001 > zeta100000);
    assertTrue(
        "Difference between zeta(100001) and zeta(100000) must be smooth (< 0.001)",
        zeta100001 - zeta100000 < 0.001);
  }

  @Test
  public void testZipfianGeneratorBoundaryConditions() {
    ZipfianGenerator generator1 = new ZipfianGenerator(1);
    assertEquals(0L, generator1.nextLong());
    assertEquals(0L, generator1.nextLong(1));

    ZipfianGenerator generator2 = new ZipfianGenerator(2);
    for (int i = 0; i < 100; i++) {
      long value = generator2.nextLong();
      assertTrue("Value must be in [0, 1]", value == 0 || value == 1);
    }
  }

  @Test
  public void testScrambledZipfianGenerator() {
    long recordCount = 10_000L;
    ScrambledZipfianGenerator generator = new ScrambledZipfianGenerator(0, recordCount - 1);
    for (int i = 0; i < 1000; i++) {
      long value = generator.nextLong();
      assertTrue("Value must be >= 0", value >= 0);
      assertTrue("Value must be < " + recordCount, value < recordCount);
      String key = generator.nextKey(12);
      assertNotNull(key);
      assertTrue("Key should start with user", key.startsWith("user"));
    }
  }

  @Test
  public void testSkewedLatestGenerator() {
    AtomicLong sequence = new AtomicLong(1000L);
    SkewedLatestGenerator generator = new SkewedLatestGenerator(sequence);
    int recentHits = 0;
    int samples = 5000;

    for (int i = 0; i < samples; i++) {
      long value = generator.nextLong();
      assertTrue("Generated key must be < basis", value < sequence.get());
      assertTrue("Generated key must be >= 0", value >= 0);
      // Check if generated key is in the top 20% most recent keys (800-1000)
      if (value >= 800) {
        recentHits++;
      }
    }

    assertTrue(
        "Latest generator should heavily favor recent items (> 50% in top 20%)",
        recentHits > samples * 0.50);
  }

  @Test
  public void testFnvHash64() {
    long hash1 = ScrambledZipfianGenerator.fnvHash64(12345L);
    long hash2 = ScrambledZipfianGenerator.fnvHash64(12345L);
    long hash3 = ScrambledZipfianGenerator.fnvHash64(54321L);
    assertEquals("Hash should be deterministic", hash1, hash2);
    assertTrue("Different inputs should produce different hashes", hash1 != hash3);

    // Boundary checks for non-negative guarantees
    assertTrue(
        "Hash of Long.MIN_VALUE must be >= 0",
        ScrambledZipfianGenerator.fnvHash64(Long.MIN_VALUE) >= 0);
    assertTrue(
        "Hash of Long.MAX_VALUE must be >= 0",
        ScrambledZipfianGenerator.fnvHash64(Long.MAX_VALUE) >= 0);
    assertTrue("Hash of 0 must be >= 0", ScrambledZipfianGenerator.fnvHash64(0L) >= 0);
    assertTrue("Hash of -1 must be >= 0", ScrambledZipfianGenerator.fnvHash64(-1L) >= 0);
  }

  @Test
  public void testYcsbWorkloadRatios() {
    int samples = 10_000;

    // Workload C: 100% read
    for (int i = 0; i < 100; i++) {
      assertEquals(YcsbWorkload.Operation.READ, YcsbWorkload.C.nextOperation());
    }

    // Workload B: 95% read, 5% update
    int readCount = 0;
    int updateCount = 0;
    for (int i = 0; i < samples; i++) {
      YcsbWorkload.Operation operation = YcsbWorkload.B.nextOperation();
      if (operation == YcsbWorkload.Operation.READ) {
        readCount++;
      } else if (operation == YcsbWorkload.Operation.UPDATE) {
        updateCount++;
      }
    }
    assertTrue("Workload B should be approx 95% reads", readCount > samples * 0.90);
    assertTrue("Workload B should have some updates", updateCount > 0);

    // Workload A: 50% read, 50% update
    readCount = 0;
    updateCount = 0;
    for (int i = 0; i < samples; i++) {
      YcsbWorkload.Operation operation = YcsbWorkload.A.nextOperation();
      if (operation == YcsbWorkload.Operation.READ) {
        readCount++;
      } else if (operation == YcsbWorkload.Operation.UPDATE) {
        updateCount++;
      }
    }
    assertTrue(
        "Workload A reads should be approx 50%",
        readCount > samples * 0.40 && readCount < samples * 0.60);
    assertTrue(
        "Workload A updates should be approx 50%",
        updateCount > samples * 0.40 && updateCount < samples * 0.60);

    // Workload D: 95% read, 5% insert
    readCount = 0;
    int insertCount = 0;
    for (int i = 0; i < samples; i++) {
      YcsbWorkload.Operation operation = YcsbWorkload.D.nextOperation();
      if (operation == YcsbWorkload.Operation.READ) {
        readCount++;
      } else if (operation == YcsbWorkload.Operation.INSERT) {
        insertCount++;
      }
    }
    assertTrue("Workload D should be approx 95% reads", readCount > samples * 0.90);
    assertTrue("Workload D should have inserts", insertCount > 0);

    // Workload E: 95% scan, 5% insert
    int scanCount = 0;
    insertCount = 0;
    for (int i = 0; i < samples; i++) {
      YcsbWorkload.Operation operation = YcsbWorkload.E.nextOperation();
      if (operation == YcsbWorkload.Operation.SCAN) {
        scanCount++;
      } else if (operation == YcsbWorkload.Operation.INSERT) {
        insertCount++;
      }
    }
    assertTrue("Workload E should be approx 95% scans", scanCount > samples * 0.90);
    assertTrue("Workload E should have inserts", insertCount > 0);

    // Workload F: 50% read, 50% read-modify-write
    readCount = 0;
    int readModifyWriteCount = 0;
    for (int i = 0; i < samples; i++) {
      YcsbWorkload.Operation operation = YcsbWorkload.F.nextOperation();
      if (operation == YcsbWorkload.Operation.READ) {
        readCount++;
      } else if (operation == YcsbWorkload.Operation.READ_MODIFY_WRITE) {
        readModifyWriteCount++;
      }
    }
    assertTrue(
        "Workload F reads should be approx 50%",
        readCount > samples * 0.40 && readCount < samples * 0.60);
    assertTrue(
        "Workload F RMW should be approx 50%",
        readModifyWriteCount > samples * 0.40 && readModifyWriteCount < samples * 0.60);
  }

  @Test
  public void testSchemaPopulatorDdlGeneration() {
    String ddl3 = YcsbSchemaPopulator.generateDdl("custom_table", 3);
    assertTrue(ddl3.contains("CREATE TABLE IF NOT EXISTS custom_table ("));
    assertTrue(ddl3.contains("id STRING(MAX),"));
    assertTrue(ddl3.contains("field0 STRING(MAX),"));
    assertTrue(ddl3.contains("field1 STRING(MAX),"));
    assertTrue(ddl3.contains("field2 STRING(MAX),"));
    assertTrue(!ddl3.contains("field3 STRING(MAX),"));
    assertTrue(ddl3.contains(") PRIMARY KEY(id)"));

    String ddl10 = YcsbSchemaPopulator.generateDdl("usertable", 10);
    assertTrue(ddl10.contains("CREATE TABLE IF NOT EXISTS usertable ("));
    assertTrue(ddl10.contains("field9 STRING(MAX),"));
  }
}
