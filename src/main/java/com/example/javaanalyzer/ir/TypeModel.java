package com.example.javaanalyzer.ir;

import java.util.ArrayList;
import java.util.List;

/**
 * Framework-independent structure of one type, including semantic hints (Javadoc, comments, names)
 * kept for future AI use.
 *
 * @param qualifiedName e.g. {@code com.example.Outer.Inner} for nested types
 * @param sourceFile path relative to the project root, with forward slashes
 * @param comment non-Javadoc comment directly attached to the declaration, or null
 * @param superClass null when not declared
 * @param constructors source and Lombok-generated constructors (name is the simple type name)
 */
public record TypeModel(
    String qualifiedName,
    String simpleName,
    String packageName,
    TypeKind kind,
    String sourceFile,
    List<String> modifiers,
    List<AnnotationModel> annotations,
    String javadoc,
    String comment,
    TypeRef superClass,
    List<TypeRef> interfaces,
    List<String> imports,
    List<FieldModel> fields,
    List<MethodModel> constructors,
    List<MethodModel> methods) {

  public TypeModel {
    modifiers = List.copyOf(modifiers);
    annotations = List.copyOf(annotations);
    interfaces = List.copyOf(interfaces);
    imports = List.copyOf(imports);
    fields = List.copyOf(fields);
    constructors = List.copyOf(constructors);
    methods = List.copyOf(methods);
  }

  /** Returns a copy with generated members appended (used for Lombok inference). */
  public TypeModel withAdditionalMembers(
      List<MethodModel> additionalConstructors, List<MethodModel> additionalMethods) {
    List<MethodModel> allConstructors = new ArrayList<>(constructors);
    allConstructors.addAll(additionalConstructors);
    List<MethodModel> allMethods = new ArrayList<>(methods);
    allMethods.addAll(additionalMethods);
    return new TypeModel(
        qualifiedName,
        simpleName,
        packageName,
        kind,
        sourceFile,
        modifiers,
        annotations,
        javadoc,
        comment,
        superClass,
        interfaces,
        imports,
        fields,
        allConstructors,
        allMethods);
  }
}
