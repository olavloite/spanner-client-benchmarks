package com.google.cloud.spanner.benchmark;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import picocli.CommandLine;

public class AnalyzerAppTest {

  @Test
  public void testHelpOption() {
    CommandLine cmd = new CommandLine(new AnalyzerApp());
    int exitCode = cmd.execute("--help");
    assertEquals(0, exitCode);
  }
}
