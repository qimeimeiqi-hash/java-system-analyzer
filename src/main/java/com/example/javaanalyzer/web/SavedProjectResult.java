package com.example.javaanalyzer.web;

import com.example.javaanalyzer.upload.InspectionIssue;
import com.example.javaanalyzer.upload.InspectionResult;
import java.util.List;

/**
 * What is saved next to the IR so that SCR-02 can be shown again after the redirect: the inspection
 * result (FR-02) without local paths, and the analysis outcome.
 *
 * @param pomPath location of pom.xml inside the archive; null if not found
 * @param springBootVersion null if unknown
 * @param sourceEncoding charset name used to read the sources
 */
public record SavedProjectResult(
    String pomPath,
    String springBootVersion,
    String sourceEncoding,
    List<InspectionIssue> inspectionIssues,
    AnalysisStatus status) {

  public SavedProjectResult {
    inspectionIssues = List.copyOf(inspectionIssues);
  }

  static SavedProjectResult of(InspectionResult inspection, AnalysisStatus status) {
    return new SavedProjectResult(
        inspection.pomPath(),
        inspection.springBootVersion(),
        inspection.sourceEncoding().name(),
        inspection.issues(),
        status);
  }
}
