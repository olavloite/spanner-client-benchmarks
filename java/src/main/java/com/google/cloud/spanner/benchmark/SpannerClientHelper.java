package com.google.cloud.spanner.benchmark;

import com.google.cloud.NoCredentials;
import com.google.cloud.spanner.SpannerOptions;

/**
 * Shared helper for building SpannerOptions with plaintext channels, emulator/mock host support,
 * and SPANNER_NUM_CHANNELS configuration.
 */
public final class SpannerClientHelper {

  private SpannerClientHelper() {}

  public static SpannerOptions createSpannerOptions(String projectId, String host) {
    SpannerOptions.Builder spannerOptionsBuilder =
        SpannerOptions.newBuilder().setProjectId(projectId);
    if (host != null && !host.isEmpty()) {
      spannerOptionsBuilder.setHost(host);
      spannerOptionsBuilder.setChannelConfigurator(builder -> builder.usePlaintext());
      spannerOptionsBuilder.setCredentials(NoCredentials.getInstance());
    }
    String numChannelsStr = System.getenv("SPANNER_NUM_CHANNELS");
    if (numChannelsStr != null && !numChannelsStr.isEmpty()) {
      try {
        int numChannels = Integer.parseInt(numChannelsStr);
        spannerOptionsBuilder.setNumChannels(numChannels);
      } catch (NumberFormatException e) {
        System.err.println("Invalid SPANNER_NUM_CHANNELS value: " + numChannelsStr);
      }
    }
    return spannerOptionsBuilder.build();
  }
}
