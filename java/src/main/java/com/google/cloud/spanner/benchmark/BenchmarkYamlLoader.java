package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.benchmark.LoadType.BenchmarkYamlConfig;
import com.google.cloud.spanner.benchmark.LoadType.StepConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loader and parser for multi-step benchmark YAML configurations. */
public final class BenchmarkYamlLoader {

  public static final String DEFAULT_YAML =
      "channelPool:\n"
          + "  minChannels: 1\n"
          + "  maxChannels: 3\n"
          + "verification:\n"
          + "  postTestWait: 5m\n"
          + "  deltaMicros: 1000\n"
          + "  enforceGates: false\n"
          + "steps:\n"
          + "  - name: warmup\n"
          + "    loadType: spiky\n"
          + "    concurrency: 16.67%\n"
          + "    duration: 5m\n"
          + "  - name: spike_70\n"
          + "    loadType: spiky\n"
          + "    concurrency: 70%\n"
          + "    duration: 10m\n"
          + "  - name: saturation_100\n"
          + "    loadType: spiky\n"
          + "    concurrency: 100%\n"
          + "    duration: 5m\n"
          + "  - name: recovery_40\n"
          + "    loadType: spiky\n"
          + "    concurrency: 40%\n"
          + "    duration: 15m\n";

  private static final Pattern PERCENT_PATTERN = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*%$");

  private BenchmarkYamlLoader() {}

  public static BenchmarkYamlConfig fromFileOrDefault(
      String pathOrName,
      LoadType cliLoadType,
      Integer minChannelsOverride,
      Integer maxChannelsOverride,
      Duration postTestWaitOverride,
      Long deltaMicrosOverride,
      Boolean enforceGatesOverride) {
    if (pathOrName == null || pathOrName.trim().isEmpty()) {
      throw new IllegalArgumentException("config cannot be null or empty");
    }
    String trimmed = pathOrName.trim();
    String yamlContent;
    if ("default".equalsIgnoreCase(trimmed)
        || "burst-profile.yaml".equalsIgnoreCase(trimmed)
        || "benchmark.yaml".equalsIgnoreCase(trimmed)) {
      yamlContent = DEFAULT_YAML;
    } else if (trimmed.contains("\n")) {
      yamlContent = trimmed;
    } else {
      try {
        Path filePath = Paths.get(trimmed);
        if (!Files.isRegularFile(filePath)) {
          throw new IllegalArgumentException("YAML configuration file not found: " + trimmed);
        }
        yamlContent = Files.readString(filePath, StandardCharsets.UTF_8);
      } catch (InvalidPathException | IOException e) {
        throw new IllegalArgumentException("Failed to read config file: " + trimmed, e);
      }
    }
    return fromYaml(
        yamlContent,
        cliLoadType,
        minChannelsOverride,
        maxChannelsOverride,
        postTestWaitOverride,
        deltaMicrosOverride,
        enforceGatesOverride);
  }

  public static BenchmarkYamlConfig fromYaml(
      String yamlContent,
      LoadType cliLoadType,
      Integer minChannelsOverride,
      Integer maxChannelsOverride,
      Duration postTestWaitOverride,
      Long deltaMicrosOverride,
      Boolean enforceGatesOverride) {
    if (yamlContent == null || yamlContent.trim().isEmpty()) {
      throw new IllegalArgumentException("YAML configuration cannot be null or empty");
    }
    int parsedMin = 1;
    int parsedMax = 3;
    boolean hasChannelPool = false;
    Duration parsedWait = Duration.ofMinutes(5);
    long parsedDelta = 1000L;
    boolean parsedGates = false;
    List<Map<String, String>> rawSteps = new ArrayList<>();
    Map<String, String> currentStep = null;
    Set<String> seenKeys = new HashSet<>();
    String section = "root";

    for (String rawLine : yamlContent.split("\\r?\\n")) {
      String uncommented = stripYamlComment(rawLine);
      String line = uncommented.trim();
      if (line.isEmpty()) {
        continue;
      }
      int indent = countLeadingSpaces(uncommented);
      if (line.equals("-") || line.startsWith("- ")) {
        if (!"steps".equals(section) || indent == 0) {
          throw new IllegalArgumentException(
              "YAML list items must be indented under 'steps:' section: " + line);
        }
        currentStep = new LinkedHashMap<>();
        rawSteps.add(currentStep);
        String rest = line.substring(1).trim();
        if (!rest.isEmpty()) {
          parseYamlListItemRest(rest, currentStep);
        }
        continue;
      }
      int colonIndex = line.indexOf(':');
      if (colonIndex <= 0) {
        throw new IllegalArgumentException("Invalid YAML syntax: " + line);
      }
      String key = line.substring(0, colonIndex).trim();
      String value = unquoteYamlValue(line.substring(colonIndex + 1).trim());
      String normKey = normalizeYamlKey(key);

      if (indent == 0) {
        currentStep = null;
        if (!value.isEmpty()
            || (!"channelpool".equals(normKey)
                && !"verification".equals(normKey)
                && !"steps".equals(normKey))) {
          throw new IllegalArgumentException(
              "Root-level configuration is not allowed; configure workload parameters inside 'steps:': "
                  + key);
        }
        if (!seenKeys.add("section:" + normKey)) {
          throw new IllegalArgumentException("Duplicate YAML section: " + key);
        }
        section = normKey;
        if ("channelpool".equals(normKey)) {
          hasChannelPool = true;
        }
        continue;
      }

      if ("steps".equals(section)) {
        if (currentStep == null || currentStep.containsKey("compact") || !isYamlStepKey(normKey)) {
          throw new IllegalArgumentException("Invalid YAML step attribute: " + line);
        }
        putStepAttribute(currentStep, normKey, value);
      } else if ("channelpool".equals(section)) {
        if (!seenKeys.add("cp:" + normKey)) {
          throw new IllegalArgumentException("Duplicate YAML key: " + key);
        }
        if ("minchannels".equals(normKey)) {
          parsedMin = Integer.parseInt(value);
        } else if ("maxchannels".equals(normKey)) {
          parsedMax = Integer.parseInt(value);
        } else {
          throw new IllegalArgumentException("Unknown channelPool key in YAML: " + key);
        }
      } else if ("verification".equals(section)) {
        if (!seenKeys.add("ver:" + normKey)) {
          throw new IllegalArgumentException("Duplicate YAML key: " + key);
        }
        if ("posttestwait".equals(normKey)) {
          parsedWait = parseStepDuration(value, key);
        } else if ("deltamicros".equals(normKey) || "deltaus".equals(normKey)) {
          parsedDelta = Long.parseLong(value);
        } else if ("enforcegates".equals(normKey)) {
          parsedGates = parseYamlBoolean(value, key);
        } else {
          throw new IllegalArgumentException("Unknown verification key in YAML: " + key);
        }
      } else {
        throw new IllegalArgumentException("Unexpected indented YAML key: " + line);
      }
    }

    if (rawSteps.isEmpty()) {
      throw new IllegalArgumentException(
          "YAML configuration must define at least one step under 'steps:'");
    }

    int resolvedMin = minChannelsOverride != null ? minChannelsOverride : parsedMin;
    int resolvedMax = maxChannelsOverride != null ? maxChannelsOverride : parsedMax;
    if (resolvedMin <= 0
        || resolvedMax < resolvedMin
        || resolvedMax > LoadType.MAX_CHANNELS_LIMIT) {
      throw new IllegalArgumentException(
          "Invalid channel bounds: min=" + resolvedMin + ", max=" + resolvedMax);
    }
    // Dynamic channel pool is enabled only if channelPool section or CLI channel overrides are
    // present
    boolean poolConfigured =
        hasChannelPool || minChannelsOverride != null || maxChannelsOverride != null;
    LoadType effectiveDefaultLoadType = cliLoadType != null ? cliLoadType : LoadType.SPIKY;
    List<StepConfig> steps =
        buildStepsFromYamlMaps(rawSteps, effectiveDefaultLoadType, resolvedMax);
    return new BenchmarkYamlConfig(
        resolvedMin,
        resolvedMax,
        poolConfigured,
        postTestWaitOverride != null ? postTestWaitOverride : parsedWait,
        deltaMicrosOverride != null ? deltaMicrosOverride : parsedDelta,
        enforceGatesOverride != null ? enforceGatesOverride : parsedGates,
        steps);
  }

  private static List<StepConfig> buildStepsFromYamlMaps(
      List<Map<String, String>> rawSteps, LoadType defaultLoadType, int maxChannels) {
    String[] rawNames = new String[rawSteps.size()];
    String[] crParts = new String[rawSteps.size()];
    String[] durParts = new String[rawSteps.size()];
    boolean allUnnamed = true;
    for (int i = 0; i < rawSteps.size(); i++) {
      Map<String, String> stepMap = rawSteps.get(i);
      if (stepMap.containsKey("compact")) {
        String token = stepMap.get("compact");
        String[] parts = token.split(":", -1);
        if (parts.length == 2) {
          rawNames[i] = null;
          crParts[i] = parts[0].trim();
          durParts[i] = parts[1].trim();
        } else if (parts.length == 3) {
          rawNames[i] = parts[0].trim();
          crParts[i] = parts[1].trim();
          durParts[i] = parts[2].trim();
          allUnnamed = false;
        } else {
          throw new IllegalArgumentException("Invalid compact step in YAML: " + token);
        }
      } else {
        rawNames[i] = stepMap.get("name");
        if (rawNames[i] != null) {
          allUnnamed = false;
        }
      }
    }

    Set<String> seenStepNames = new HashSet<>();
    List<StepConfig> steps = new ArrayList<>(rawSteps.size());
    for (int i = 0; i < rawSteps.size(); i++) {
      Map<String, String> stepMap = rawSteps.get(i);
      ChannelPoolPhase phase = resolveStepPhase(rawNames[i], i, rawSteps.size(), allUnnamed);
      String stepName = rawNames[i];
      if (stepName == null || stepName.trim().isEmpty()) {
        stepName = phase != null ? phase.getMetricLabel() : ("step_" + (i + 1));
      }
      if (!seenStepNames.add(stepName.toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException("Duplicate step name in YAML: " + stepName);
      }

      LoadType stepLoadType = defaultLoadType;
      if (stepMap.containsKey("loadtype")) {
        stepLoadType = LoadType.fromString(stepMap.get("loadtype"));
      }

      int tps = 0;
      if (stepMap.containsKey("tps")) {
        tps = (int) Math.round(Double.parseDouble(stepMap.get("tps")));
      }

      int concurrency;
      if (stepMap.containsKey("compact")) {
        concurrency = parseStepConcurrency(crParts[i], maxChannels, stepName);
      } else if (stepMap.containsKey("concurrency")) {
        concurrency = parseStepConcurrency(stepMap.get("concurrency"), maxChannels, stepName);
      } else if (stepMap.containsKey("threads")) {
        concurrency = Integer.parseInt(stepMap.get("threads"));
      } else if (tps > 0) {
        concurrency = 16;
      } else if (phase != null) {
        concurrency = defaultPhaseConcurrency(phase, maxChannels);
      } else {
        concurrency = 1;
      }

      Duration duration;
      if (stepMap.containsKey("compact")) {
        duration = parseStepDuration(durParts[i], stepName + ".duration");
      } else if (stepMap.containsKey("duration")) {
        duration = parseStepDuration(stepMap.get("duration"), stepName + ".duration");
      } else {
        throw new IllegalArgumentException("Step " + stepName + " is missing required duration");
      }

      Duration cycleDuration =
          stepMap.containsKey("cycleduration")
              ? parseStepDuration(stepMap.get("cycleduration"), stepName + ".cycleDuration")
              : Duration.ofHours(1);
      double peakFactor =
          stepMap.containsKey("peakfactor") ? Double.parseDouble(stepMap.get("peakfactor")) : 2.0;
      double burstFactor =
          stepMap.containsKey("burstfactor") ? Double.parseDouble(stepMap.get("burstfactor")) : 1.0;
      double burstDuration =
          stepMap.containsKey("burstduration")
              ? Double.parseDouble(stepMap.get("burstduration"))
              : 1.0;
      double burstFraction =
          stepMap.containsKey("burstfraction")
              ? Double.parseDouble(stepMap.get("burstfraction"))
              : 0.1;

      steps.add(
          new StepConfig(
              stepName,
              stepLoadType,
              duration,
              concurrency,
              tps,
              cycleDuration,
              peakFactor,
              burstFactor,
              burstDuration,
              burstFraction,
              phase));
    }
    return steps;
  }

  static int defaultPhaseConcurrency(ChannelPoolPhase phase, int maxChannels) {
    if (phase == null) {
      return 1;
    }
    switch (phase) {
      case WARMUP:
        return UnifiedProfileConfig.defaultWarmupConcurrency(maxChannels);
      case SPIKE_70:
        return UnifiedProfileConfig.defaultSpikeConcurrency(maxChannels);
      case SATURATION_100:
        return UnifiedProfileConfig.defaultSaturationConcurrency(maxChannels);
      case RECOVERY_40:
        return UnifiedProfileConfig.defaultRecoveryConcurrency(maxChannels);
      default:
        return 1;
    }
  }

  static ChannelPoolPhase resolveStepPhase(
      String name, int index, int totalSteps, boolean allUnnamed) {
    if (name != null) {
      String n = name.trim().toLowerCase(Locale.ROOT);
      if ("warmup".equals(n) || "warm_up".equals(n)) {
        return ChannelPoolPhase.WARMUP;
      }
      if ("spike".equals(n) || "spike_70".equals(n) || "spike-70".equals(n)) {
        return ChannelPoolPhase.SPIKE_70;
      }
      if ("saturation".equals(n) || "saturation_100".equals(n) || "saturation-100".equals(n)) {
        return ChannelPoolPhase.SATURATION_100;
      }
      if ("recovery".equals(n) || "recovery_40".equals(n) || "recovery-40".equals(n)) {
        return ChannelPoolPhase.RECOVERY_40;
      }
    }
    if (allUnnamed && totalSteps == 4) {
      switch (index) {
        case 0:
          return ChannelPoolPhase.WARMUP;
        case 1:
          return ChannelPoolPhase.SPIKE_70;
        case 2:
          return ChannelPoolPhase.SATURATION_100;
        case 3:
          return ChannelPoolPhase.RECOVERY_40;
        default:
          return null;
      }
    }
    return null;
  }

  public static int parseStepConcurrency(String rawValue, int maxChannels, String stepName) {
    if (rawValue == null || rawValue.trim().isEmpty()) {
      throw new IllegalArgumentException("Concurrency cannot be empty for " + stepName);
    }
    String trimmed = rawValue.trim();
    Matcher m = PERCENT_PATTERN.matcher(trimmed);
    if (m.matches()) {
      double percent = Double.parseDouble(m.group(1));
      if (Double.isNaN(percent) || Double.isInfinite(percent) || percent <= 0.0) {
        throw new IllegalArgumentException(
            "Invalid concurrency percentage for " + stepName + ": " + rawValue);
      }
      int computed =
          (int) Math.round((percent / 100.0) * maxChannels * LoadType.STREAMS_PER_CHANNEL);
      if (computed <= 0) {
        throw new IllegalArgumentException(
            "Computed concurrency <= 0 for " + stepName + " with " + rawValue);
      }
      return computed;
    }
    try {
      int concurrency = Integer.parseInt(trimmed);
      if (concurrency <= 0 || concurrency > LoadType.MAX_STEP_CONCURRENCY) {
        throw new IllegalArgumentException(
            "Concurrency must be between 1 and "
                + LoadType.MAX_STEP_CONCURRENCY
                + " for "
                + stepName
                + " (got "
                + concurrency
                + ")");
      }
      return concurrency;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "Invalid concurrency value for " + stepName + ": " + rawValue, e);
    }
  }

  public static Duration parseStepDuration(String value, String key) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException("Duration cannot be empty for " + key);
    }
    String trimmed = value.trim();
    if ("inf".equalsIgnoreCase(trimmed) || "infinite".equalsIgnoreCase(trimmed)) {
      throw new IllegalArgumentException("Infinite duration is not allowed for " + key);
    }
    Duration duration = AbstractBenchmark.parseDuration(trimmed);
    if (duration == null || duration.isNegative()) {
      throw new IllegalArgumentException("Invalid duration for " + key + ": " + value);
    }
    return duration;
  }

  private static void parseYamlListItemRest(String rest, Map<String, String> currentStep) {
    int colon = rest.indexOf(':');
    if (colon > 0) {
      String candidateKey = rest.substring(0, colon).trim();
      String norm = normalizeYamlKey(candidateKey);
      if (isYamlStepKey(norm)) {
        String val = unquoteYamlValue(rest.substring(colon + 1).trim());
        putStepAttribute(currentStep, norm, val);
        return;
      }
    }
    currentStep.put("compact", rest);
  }

  private static int countLeadingSpaces(String s) {
    int count = 0;
    while (count < s.length() && s.charAt(count) == ' ') {
      count++;
    }
    return count;
  }

  private static boolean isYamlStepKey(String normKey) {
    return "name".equals(normKey)
        || "loadtype".equals(normKey)
        || "duration".equals(normKey)
        || "concurrency".equals(normKey)
        || "cr".equals(normKey)
        || "threads".equals(normKey)
        || "tps".equals(normKey)
        || "cycleduration".equals(normKey)
        || "peakfactor".equals(normKey)
        || "burstfactor".equals(normKey)
        || "burstduration".equals(normKey)
        || "burstfraction".equals(normKey);
  }

  private static void putStepAttribute(
      Map<String, String> currentStep, String normKey, String value) {
    if ("concurrency".equals(normKey) || "cr".equals(normKey)) {
      normKey = "concurrency";
    }
    if (currentStep.put(normKey, value) != null) {
      throw new IllegalArgumentException("Duplicate step attribute: " + normKey);
    }
  }

  private static String normalizeYamlKey(String key) {
    return key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
  }

  private static String stripYamlComment(String line) {
    int commentIdx = line.indexOf('#');
    if (commentIdx < 0) {
      return line;
    }
    return line.substring(0, commentIdx);
  }

  private static String unquoteYamlValue(String value) {
    if ((value.startsWith("\"") && value.endsWith("\""))
        || (value.startsWith("'") && value.endsWith("'"))) {
      if (value.length() >= 2) {
        return value.substring(1, value.length() - 1);
      }
    }
    return value;
  }

  private static boolean parseYamlBoolean(String value, String key) {
    if ("true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "1".equals(value)) {
      return true;
    }
    if ("false".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value) || "0".equals(value)) {
      return false;
    }
    throw new IllegalArgumentException("Invalid boolean for " + key + ": " + value);
  }
}
