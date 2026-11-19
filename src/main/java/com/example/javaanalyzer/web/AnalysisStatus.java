package com.example.javaanalyzer.web;

/** Outcome of processing an uploaded project, saved so that the result page can be reopened. */
public enum AnalysisStatus {
  /** The inspection found a reason that the project cannot be analyzed. */
  UNSUPPORTED,
  SUCCEEDED,
  /** The analysis threw; details are only in the server log. */
  FAILED
}
