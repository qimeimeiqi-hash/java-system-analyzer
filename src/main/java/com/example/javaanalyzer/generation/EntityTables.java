package com.example.javaanalyzer.generation;

import com.example.javaanalyzer.ir.ColumnModel;
import com.example.javaanalyzer.ir.EntityKind;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.JoinColumnModel;
import com.example.javaanalyzer.ir.JoinTableModel;
import com.example.javaanalyzer.ir.RelationModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tables of the ER screen (FR-07, SCR-05). Names that the source does not state are shown as 未指定;
 * default table and column names of the naming strategy are never guessed (NFR-06).
 */
public final class EntityTables {

  public static final List<String> COLUMN_HEADERS =
      List.of("フィールド", "型", "カラム名", "PK", "生成戦略", "NULL可", "長さ", "一意", "Enum型", "埋め込み");

  public static final List<String> RELATION_HEADERS =
      List.of(
          "フィールド",
          "種類",
          "相手",
          "所有側",
          "mappedBy",
          "結合カラム",
          "結合テーブル",
          "optional",
          "fetch",
          "cascade");

  private EntityTables() {}

  public static Table entityList(List<EntityModel> entities) {
    List<List<Cell>> rows = new ArrayList<>();
    for (EntityModel entity : sorted(entities)) {
      boolean isEntity = entity.kind() == EntityKind.ENTITY;
      rows.add(
          List.of(
              Cell.typeName(entity.typeName()),
              Cell.of(Labels.entityKind(entity.kind())),
              Cell.of(isEntity ? Labels.orUnspecified(entity.entityName()) : Labels.DASH),
              Cell.of(isEntity ? Labels.orUnspecified(entity.tableName()) : Labels.DASH),
              Cell.of(isEntity ? Labels.orUnspecified(entity.schema()) : Labels.DASH),
              Cell.type(entity.superType())));
    }
    return new Table(List.of("エンティティ", "種類", "エンティティ名", "テーブル名", "スキーマ", "親クラス"), rows);
  }

  public static Table columns(EntityModel entity) {
    List<List<Cell>> rows = new ArrayList<>();
    for (ColumnModel column : entity.columns()) {
      rows.add(
          List.of(
              Cell.of(column.fieldName()),
              Cell.type(column.type()),
              Cell.of(Labels.orUnspecified(column.columnName())),
              Cell.of(column.id() ? "○" : Labels.DASH),
              Cell.of(Labels.orDash(column.generationStrategy())),
              Cell.of(Labels.yesNo(column.nullable())),
              Cell.of(Labels.orDash(column.length())),
              Cell.of(Labels.yesNo(column.unique())),
              Cell.of(Labels.orDash(column.enumType())),
              Cell.of(column.embedded() ? "○" : Labels.DASH)));
    }
    return new Table(COLUMN_HEADERS, rows);
  }

  public static Table relations(EntityModel entity) {
    List<List<Cell>> rows = new ArrayList<>();
    for (RelationModel relation : entity.relations()) {
      rows.add(
          List.of(
              Cell.of(relation.fieldName()),
              Cell.of(Labels.relationKind(relation.kind())),
              Cell.type(relation.targetType()),
              Cell.of(relation.owningSide() ? "○" : "×"),
              Cell.of(Labels.orDash(relation.mappedBy())),
              Cell.of(joinColumns(relation.joinColumns())),
              Cell.of(joinTable(relation.joinTable())),
              Cell.of(Labels.yesNo(relation.optional())),
              Cell.of(Labels.orDash(relation.fetch())),
              Cell.of(Labels.join(relation.cascade()))));
    }
    return new Table(RELATION_HEADERS, rows);
  }

  static List<EntityModel> sorted(List<EntityModel> entities) {
    return entities.stream().sorted(Comparator.comparing(EntityModel::typeName)).toList();
  }

  private static String joinColumns(List<JoinColumnModel> columns) {
    return Labels.join(columns.stream().map(c -> Labels.orUnspecified(c.name())).toList());
  }

  private static String joinTable(JoinTableModel joinTable) {
    if (joinTable == null) {
      return Labels.DASH;
    }
    String name = Labels.orUnspecified(joinTable.name());
    if (joinTable.joinColumns().isEmpty() && joinTable.inverseJoinColumns().isEmpty()) {
      return name;
    }
    return name
        + "（"
        + joinColumns(joinTable.joinColumns())
        + " / 逆側 "
        + joinColumns(joinTable.inverseJoinColumns())
        + "）";
  }
}
