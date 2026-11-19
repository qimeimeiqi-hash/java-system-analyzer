package com.example.javaanalyzer.generation;

import java.util.List;

/**
 * A format-neutral table. Screens, Markdown, Word and Excel render the same tables so that every
 * output shows the same content.
 */
public record Table(List<String> headers, List<List<Cell>> rows) {

  public Table {
    headers = List.copyOf(headers);
    rows = rows.stream().map(List::copyOf).toList();
  }

  public boolean isEmpty() {
    return rows.isEmpty();
  }

  /** The cell texts, row by row. */
  public List<List<String>> texts() {
    return rows.stream().map(row -> row.stream().map(Cell::text).toList()).toList();
  }
}
