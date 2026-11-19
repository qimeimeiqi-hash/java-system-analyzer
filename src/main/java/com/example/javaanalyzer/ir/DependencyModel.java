package com.example.javaanalyzer.ir;

/**
 * One injection target of a component. Every injection has at least one entry, so dependencies that
 * cannot be resolved stay visible instead of silently disappearing. An injection receiving several
 * beans, such as {@code List<Foo>}, has one entry per bean.
 *
 * @param fromType qualified name of the component receiving the injection
 * @param memberName field name, or parameter name for constructor / setter injection
 * @param declaredType the type as written at the injection point
 * @param resolvedType qualified name of the project type depended on; null when {@code resolution}
 *     is AMBIGUOUS, EXTERNAL or UNRESOLVED
 */
public record DependencyModel(
    String fromType,
    String memberName,
    InjectionKind injectionKind,
    TypeRef declaredType,
    String resolvedType,
    DependencyResolution resolution) {}
