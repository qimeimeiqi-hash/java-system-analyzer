package com.example.javaanalyzer.ir;

import java.util.List;

/**
 * A field declaration (one entry per variable when several are declared together).
 *
 * @param comment non-Javadoc comment directly attached to the declaration, or null
 * @param initialized whether the declaration has an initializer (needed for Lombok constructors)
 */
public record FieldModel(
    String name,
    TypeRef type,
    List<String> modifiers,
    List<AnnotationModel> annotations,
    String javadoc,
    String comment,
    boolean initialized) {

  public FieldModel {
    modifiers = List.copyOf(modifiers);
    annotations = List.copyOf(annotations);
  }
}
