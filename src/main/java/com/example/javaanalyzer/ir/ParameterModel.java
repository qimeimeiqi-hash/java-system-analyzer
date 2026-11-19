package com.example.javaanalyzer.ir;

import java.util.List;

/** A method or constructor parameter. */
public record ParameterModel(String name, TypeRef type, List<AnnotationModel> annotations) {

  public ParameterModel {
    annotations = List.copyOf(annotations);
  }
}
