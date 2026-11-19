package com.example.javaanalyzer.upload;

/** A finding of the pre-analysis inspection. ERROR makes the project unsupported. */
public record InspectionIssue(Severity severity, String message) {

  public enum Severity {
    ERROR,
    WARNING
  }

  public static InspectionIssue error(String message) {
    return new InspectionIssue(Severity.ERROR, message);
  }

  public static InspectionIssue warning(String message) {
    return new InspectionIssue(Severity.WARNING, message);
  }

  public boolean isError() {
    return severity == Severity.ERROR;
  }
}
