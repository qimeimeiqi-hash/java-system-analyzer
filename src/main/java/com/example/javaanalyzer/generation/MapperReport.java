package com.example.javaanalyzer.generation;

import java.util.List;

/**
 * One mapper of the MyBatis screen (FR-15, SCR-08).
 *
 * @param interfaceType qualified name of the mapper interface; null for a namespace without one
 * @param title the interface's simple name, or the namespace when there is no interface
 * @param methods the interface's methods; null for a namespace without an interface
 */
public record MapperReport(
    String interfaceType,
    String title,
    List<KeyValue> information,
    List<String> notes,
    Table methods,
    List<StatementReport> statements) {

  public MapperReport {
    information = List.copyOf(information);
    notes = List.copyOf(notes);
    statements = List.copyOf(statements);
  }
}
