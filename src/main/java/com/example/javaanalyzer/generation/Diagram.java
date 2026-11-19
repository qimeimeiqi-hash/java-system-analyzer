package com.example.javaanalyzer.generation;

/**
 * Mermaid code of one diagram. The id identifies the diagram when the browser sends its PNG for the
 * Word/Excel export (SCR-11), so it must be stable for the same IR.
 *
 * @param id identifier made of letters, digits and hyphens
 */
public record Diagram(String id, String title, String code) {}
