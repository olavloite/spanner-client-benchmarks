package com.google.cloud.spanner.benchmark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.cloud.spanner.benchmark.LoadType.BenchmarkYamlConfig;
import com.google.cloud.spanner.benchmark.LoadType.StepConfig;
import java.time.Duration;
import org.junit.Test;

public class MultiStepConfigTest {

  @Test
  public void testStepConfigRejectsInvalidParameters() {
    // peakFactor <= 0
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StepConfig(
                "step1",
                LoadType.GRADUAL,
                Duration.ofMinutes(1),
                10,
                100,
                Duration.ofMinutes(5),
                0.0,
                2.0,
                1.0,
                0.1,
                null));

    // burstFactor <= 0
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StepConfig(
                "step2",
                LoadType.SPIKY,
                Duration.ofMinutes(1),
                10,
                100,
                Duration.ofMinutes(5),
                2.0,
                -1.0,
                1.0,
                0.1,
                null));

    // burstDuration <= 0
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StepConfig(
                "step3",
                LoadType.SPIKY,
                Duration.ofMinutes(1),
                10,
                100,
                Duration.ofMinutes(5),
                2.0,
                2.0,
                0.0,
                0.1,
                null));

    // burstFraction <= 0 or >= 1.0
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StepConfig(
                "step4",
                LoadType.SPIKY,
                Duration.ofMinutes(1),
                10,
                100,
                Duration.ofMinutes(5),
                2.0,
                2.0,
                1.0,
                1.5,
                null));
  }

  @Test
  public void testDynamicChannelPoolOnlyConfiguredWhenExplicit() {
    String yamlWithoutChannelPool =
        "steps:\n"
            + "  - name: steady_step\n"
            + "    loadType: steady\n"
            + "    tps: 50\n"
            + "    duration: 1m\n";
    BenchmarkYamlConfig cfgWithoutPool = BenchmarkYamlConfig.fromYaml(yamlWithoutChannelPool);
    assertFalse(cfgWithoutPool.isChannelPoolConfigured());

    String yamlWithChannelPool =
        "channelPool:\n"
            + "  minChannels: 2\n"
            + "  maxChannels: 4\n"
            + "steps:\n"
            + "  - name: steady_step\n"
            + "    loadType: steady\n"
            + "    tps: 50\n"
            + "    duration: 1m\n";
    BenchmarkYamlConfig cfgWithPool = BenchmarkYamlConfig.fromYaml(yamlWithChannelPool);
    assertTrue(cfgWithPool.isChannelPoolConfigured());
    assertEquals(2, cfgWithPool.getMinChannels());
    assertEquals(4, cfgWithPool.getMaxChannels());
  }

  @Test
  public void testComputeIntervalNanosSpikyAndGradual() {
    StepConfig spikyStep =
        new StepConfig(
            "spiky_step",
            LoadType.SPIKY,
            Duration.ofMinutes(1),
            10,
            100,
            Duration.ofHours(1),
            2.0,
            2.0,
            1.0,
            0.1,
            null);
    long intervalInBurst = spikyStep.computeIntervalNanos(0);
    // In burst (tps = 100 * 2 = 200), interval is 1s / 200 = 5ms
    assertEquals(5_000_000L, intervalInBurst);

    StepConfig gradualStep =
        new StepConfig(
            "gradual_step",
            LoadType.GRADUAL,
            Duration.ofMinutes(1),
            10,
            100,
            Duration.ofMinutes(10),
            2.0,
            2.0,
            1.0,
            0.1,
            null);
    long intervalStart = gradualStep.computeIntervalNanos(0);
    // At t=0, cos(-PI) = -1, rate = tps + tps*(2-1)*(-1) = 0 -> clamped to 1.0 TPS -> 1s interval
    assertEquals(1_000_000_000L, intervalStart);
  }
}
