package com.example.javaanalyzer.ir;

/**
 * A persistent attribute mapped to a column. Only values written in annotations are recorded; null
 * means "not specified" (defaults depend on runtime configuration and are not guessed).
 *
 * @param columnName explicit {@code @Column(name)}, or null
 * @param generationStrategy {@code @GeneratedValue(strategy)} as written; empty string when
 *     {@code @GeneratedValue} has no strategy; null when not generated
 * @param enumType {@code @Enumerated} value as written; empty string when no value; null when not
 *     annotated
 * @param embedded true for {@code @Embedded} / {@code @EmbeddedId} attributes
 */
public record ColumnModel(
    String fieldName,
    TypeRef type,
    String columnName,
    boolean id,
    String generationStrategy,
    Boolean nullable,
    Integer length,
    Boolean unique,
    String enumType,
    boolean embedded) {}
