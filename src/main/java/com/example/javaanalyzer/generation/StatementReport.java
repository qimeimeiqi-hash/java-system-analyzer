package com.example.javaanalyzer.generation;

import java.util.List;

/**
 * One SQL statement of a mapper, with the SQL exactly as written (no interpretation, FR-15).
 *
 * @param heading tag, id and source, e.g. "select findById（XML）"
 */
public record StatementReport(String heading, String sql, List<String> notes) {

  public StatementReport {
    notes = List.copyOf(notes);
  }
}
