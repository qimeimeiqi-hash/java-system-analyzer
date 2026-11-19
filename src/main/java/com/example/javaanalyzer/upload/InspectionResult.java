package com.example.javaanalyzer.upload;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Result of checking whether an extracted project can be analyzed.
 *
 * @param projectRoot absolute directory containing pom.xml (input for the analysis); null if not
 *     found
 * @param pomPath location of pom.xml relative to the extracted archive, for display; null if not
 *     found
 * @param springBootVersion version literally written in pom.xml (property resolved); null if
 *     unknown
 * @param issues reasons for being unsupported (ERROR) and warnings
 * @param sourceEncoding encoding declared by {@code project.build.sourceEncoding}, or UTF-8
 */
public record InspectionResult(
    Path projectRoot,
    String pomPath,
    String springBootVersion,
    List<InspectionIssue> issues,
    Charset sourceEncoding) {

  public InspectionResult {
    issues = List.copyOf(issues);
  }

  public InspectionResult(
      Path projectRoot, String pomPath, String springBootVersion, List<InspectionIssue> issues) {
    this(projectRoot, pomPath, springBootVersion, issues, StandardCharsets.UTF_8);
  }

  public boolean supported() {
    return issues.stream().noneMatch(InspectionIssue::isError);
  }
}
