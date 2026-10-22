package com.google.cloud.spanner.benchmark;

import java.util.Locale;

public enum ChannelPoolPhase {
  WARMUP,
  SPIKE_70,
  SATURATION_100,
  RECOVERY_40;

  public String getMetricLabel() {
    return name().toLowerCase(Locale.ROOT);
  }
}
