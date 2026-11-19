package com.example.javaanalyzer.ir;

/**
 * A binding of external configuration properties to a type or bean (FR-16).
 *
 * @param typeName qualified name of the type carrying the annotation or declaring the factory
 *     method
 * @param beanMethodName factory method carrying the annotation; null when it is on the type
 * @param prefix prefix as a string value, or the raw expression when it is not a literal; null when
 *     not specified
 * @param prefixResolved false when the prefix is kept as a raw expression, e.g. a constant
 */
public record ConfigurationPropertiesModel(
    String typeName, String beanMethodName, String prefix, boolean prefixResolved) {}
