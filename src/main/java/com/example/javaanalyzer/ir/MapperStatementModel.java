package com.example.javaanalyzer.ir;

/**
 * One SQL statement of a mapper (FR-15). The SQL is kept as written and is not interpreted.
 *
 * @param tag {@code select}, {@code insert}, {@code update}, {@code delete} or {@code sql}
 * @param id {@code id} of the XML element, or the method name for an annotation
 * @param sql the text as written, including dynamic tags such as {@code <if>} and without
 *     surrounding whitespace; for several annotation values, the values joined by line breaks
 * @param sqlResolved false when {@code sql} holds a raw expression instead of SQL, e.g. a constant
 *     or a provider annotation
 * @param methodName the mapper method with this name; null when there is none
 */
public record MapperStatementModel(
    String tag,
    String id,
    String sql,
    boolean sqlResolved,
    StatementSource source,
    String methodName) {}
