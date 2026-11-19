package com.example.javaanalyzer.generation;

import java.util.List;

/**
 * A titled part of a page or document: optional key-values, an optional table and notes.
 *
 * @param emptyMessage shown instead of the table when it has no rows; null when the table is absent
 */
public record Section(
    String title, List<KeyValue> keyValues, Table table, String emptyMessage, List<String> notes) {

  public static final String NOT_DETECTED = "検出されませんでした";

  public Section {
    keyValues = List.copyOf(keyValues);
    notes = List.copyOf(notes);
  }

  public static Section ofTable(String title, Table table, String... notes) {
    return new Section(title, List.of(), table, NOT_DETECTED, List.of(notes));
  }

  public static Section ofKeyValues(String title, List<KeyValue> keyValues, String... notes) {
    return new Section(title, keyValues, null, null, List.of(notes));
  }
}
