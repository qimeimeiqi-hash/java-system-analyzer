package com.example.javaanalyzer.analysis.plugin;

import com.example.javaanalyzer.analysis.AnalysisContext;

/**
 * A framework-specific analyzer. Implementations are Spring beans; the core runs every plugin that
 * supports the project, in registration order.
 */
public interface AnalyzerPlugin {

  /** Human-readable plugin name, e.g. "Spring Boot". */
  String name();

  boolean supports(AnalysisContext context);

  /** Adds framework-specific findings (components, endpoints, entities, ...) to the context. */
  void analyze(AnalysisContext context);
}
