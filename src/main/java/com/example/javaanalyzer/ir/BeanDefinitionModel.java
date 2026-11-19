package com.example.javaanalyzer.ir;

import java.util.List;

/**
 * A bean defined by a factory method (FR-16).
 *
 * @param declaringType qualified name of the type declaring the method
 * @param beanNames names written in the annotation, as string values or raw expressions; empty when
 *     not specified (the default name is not guessed)
 * @param parameters parameters of the factory method, kept as written
 */
public record BeanDefinitionModel(
    String declaringType,
    String methodName,
    List<String> beanNames,
    TypeRef returnType,
    List<ParameterModel> parameters) {

  public BeanDefinitionModel {
    beanNames = List.copyOf(beanNames);
    parameters = List.copyOf(parameters);
  }
}
