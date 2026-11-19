package com.example.javaanalyzer.generation;

import com.example.javaanalyzer.ir.TypeRef;

/**
 * One table cell. Renderers show {@code text}; when {@code qualifiedName} is set it is shown as a
 * tooltip and, if it names a type of the project, linked to its class page (screen-design 2.2).
 *
 * @param qualifiedName fully qualified type name behind the text; null for plain text
 */
public record Cell(String text, String qualifiedName) {

  public static Cell of(String text) {
    return new Cell(text, null);
  }

  /**
   * A type as written in the source; unresolved types keep their source text (screen-design 2.2).
   */
  public static Cell type(TypeRef type) {
    return type == null ? of(Labels.DASH) : new Cell(type.text(), type.qualifiedName());
  }

  /** A project type given by its qualified name, shown by its simple name. */
  public static Cell typeName(String qualifiedName) {
    return qualifiedName == null
        ? of(Labels.DASH)
        : new Cell(Labels.simpleName(qualifiedName), qualifiedName);
  }
}
