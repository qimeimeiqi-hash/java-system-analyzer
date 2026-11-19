package com.example.javaanalyzer.ir;

import java.util.List;

/**
 * A persistent type (FR-07).
 *
 * @param entityName explicit {@code @Entity(name)}, or null
 * @param tableName explicit {@code @Table(name)}, or null (default naming is not guessed)
 * @param schema explicit {@code @Table(schema)}, or null
 * @param superType declared superclass, or null
 */
public record EntityModel(
    String typeName,
    EntityKind kind,
    String entityName,
    String tableName,
    String schema,
    TypeRef superType,
    List<ColumnModel> columns,
    List<RelationModel> relations) {

  public EntityModel {
    columns = List.copyOf(columns);
    relations = List.copyOf(relations);
  }
}
