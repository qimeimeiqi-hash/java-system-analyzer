package com.example.javaanalyzer.analysis;

import com.example.javaanalyzer.ir.AnnotationModel;
import com.example.javaanalyzer.ir.FieldModel;
import com.example.javaanalyzer.ir.MethodModel;
import com.example.javaanalyzer.ir.ParameterModel;
import com.example.javaanalyzer.ir.TypeModel;
import java.util.Collection;
import java.util.List;

/**
 * Fills in qualified names of annotations that structure extraction left unresolved because they
 * come from an on-demand import (e.g. {@code import jakarta.persistence.*;}). Only names a plugin
 * recognizes are filled, using the same rule as {@link TypeNameResolver#refersTo}, so the
 * generation layer can identify annotations from the IR alone.
 */
final class AnnotationQualifier {

  private final TypeNameResolver resolver;
  private final Collection<String> knownQualifiedNames;

  AnnotationQualifier(TypeNameResolver resolver, Collection<String> knownQualifiedNames) {
    this.resolver = resolver;
    this.knownQualifiedNames = knownQualifiedNames;
  }

  TypeModel qualify(TypeModel type) {
    return new TypeModel(
        type.qualifiedName(),
        type.simpleName(),
        type.packageName(),
        type.kind(),
        type.sourceFile(),
        type.modifiers(),
        annotations(type.annotations()),
        type.javadoc(),
        type.comment(),
        type.superClass(),
        type.interfaces(),
        type.imports(),
        type.fields().stream().map(this::field).toList(),
        type.constructors().stream().map(this::method).toList(),
        type.methods().stream().map(this::method).toList());
  }

  private FieldModel field(FieldModel field) {
    return new FieldModel(
        field.name(),
        field.type(),
        field.modifiers(),
        annotations(field.annotations()),
        field.javadoc(),
        field.comment(),
        field.initialized());
  }

  List<ParameterModel> parameters(List<ParameterModel> parameters) {
    return parameters.stream()
        .map(p -> new ParameterModel(p.name(), p.type(), annotations(p.annotations())))
        .toList();
  }

  private MethodModel method(MethodModel method) {
    return new MethodModel(
        method.name(),
        method.returnType(),
        parameters(method.parameters()),
        method.modifiers(),
        annotations(method.annotations()),
        method.javadoc(),
        method.comment(),
        method.bodyComments(),
        method.origin(),
        method.generatedBy());
  }

  private List<AnnotationModel> annotations(List<AnnotationModel> annotations) {
    return annotations.stream().map(this::annotation).toList();
  }

  private AnnotationModel annotation(AnnotationModel annotation) {
    if (annotation.qualifiedName() != null) {
      return annotation;
    }
    return knownQualifiedNames.stream()
        .filter(known -> resolver.refersTo(annotation.name(), known))
        .findFirst()
        .map(known -> new AnnotationModel(annotation.name(), known, annotation.attributes()))
        .orElse(annotation);
  }
}
