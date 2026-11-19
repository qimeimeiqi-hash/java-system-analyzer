package com.example.javaanalyzer.generation;

import java.util.List;

/**
 * The structural description of one class (FR-09, SCR-06).
 *
 * @param heading simple name and role, e.g. "OrderService（Service）"
 * @param summary fixed sentences built from IR facts only (no AI, NFR-02)
 */
public record ClassOverview(
    String typeName,
    String heading,
    ClassTemplate template,
    String summary,
    List<Section> sections) {

  public ClassOverview {
    sections = List.copyOf(sections);
  }
}
