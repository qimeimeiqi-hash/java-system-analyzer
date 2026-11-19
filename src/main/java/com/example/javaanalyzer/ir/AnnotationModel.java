package com.example.javaanalyzer.ir;

import java.util.Map;

/**
 * An annotation kept as written. Values are raw expression source text so that nothing is lost.
 *
 * @param name name as written, e.g. {@code OneToMany} or {@code jakarta.persistence.OneToMany}
 * @param qualifiedName resolved fully qualified name, or null if unresolved
 * @param attributes attribute name to raw expression; a single-value annotation uses {@code value}
 */
public record AnnotationModel(String name, String qualifiedName, Map<String, String> attributes) {

  public AnnotationModel {
    attributes = Map.copyOf(attributes);
  }
}
