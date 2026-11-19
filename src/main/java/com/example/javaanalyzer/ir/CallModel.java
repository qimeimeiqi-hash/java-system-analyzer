package com.example.javaanalyzer.ir;

import java.util.List;

/**
 * A method call on an injected field, detected heuristically (FR-18). Overloads and polymorphism
 * are not resolved: only the method name and the number of arguments are known.
 *
 * @param fromParameterTypes parameter types of the calling method as written, to tell overloads of
 *     the caller apart
 * @param fieldName the injected field the method is called on
 * @param methodReference true for a method reference such as {@code repository::save}
 * @param argumentCount number of arguments; null for a method reference
 * @param declaredType type of the field as declared
 * @param targetType resolved type of the matching {@link DependencyModel}; null when {@code
 *     resolution} is AMBIGUOUS or UNRESOLVED
 * @param resolution resolution of the matching {@link DependencyModel}
 * @param line 1-based source line of the called method name
 */
public record CallModel(
    String fromType,
    String fromMethod,
    List<String> fromParameterTypes,
    String fieldName,
    String methodName,
    boolean methodReference,
    Integer argumentCount,
    TypeRef declaredType,
    String targetType,
    DependencyResolution resolution,
    int line) {

  public CallModel {
    fromParameterTypes = List.copyOf(fromParameterTypes);
  }
}
