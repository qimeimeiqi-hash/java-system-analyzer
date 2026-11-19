package com.example.javaanalyzer.generation;

import static com.example.javaanalyzer.generation.IrFixtures.type;
import static com.example.javaanalyzer.generation.IrFixtures.unresolvedType;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.ir.ColumnModel;
import com.example.javaanalyzer.ir.EntityKind;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.JoinColumnModel;
import com.example.javaanalyzer.ir.JoinTableModel;
import com.example.javaanalyzer.ir.RelationKind;
import com.example.javaanalyzer.ir.RelationModel;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EntityTablesTest {

  private static final String P = "com.example.shop.domain.";

  private static final EntityModel ORDER =
      new EntityModel(
          P + "PurchaseOrder",
          EntityKind.ENTITY,
          null,
          "purchase_orders",
          null,
          type(P + "BaseEntity"),
          List.of(
              new ColumnModel(
                  "id",
                  type("java.lang.Long"),
                  null,
                  true,
                  "IDENTITY",
                  null,
                  null,
                  null,
                  null,
                  false),
              new ColumnModel(
                  "code",
                  type("java.lang.String"),
                  "order_code",
                  false,
                  null,
                  false,
                  20,
                  true,
                  null,
                  false),
              new ColumnModel(
                  "status",
                  type(P + "OrderStatus"),
                  null,
                  false,
                  null,
                  null,
                  null,
                  null,
                  "STRING",
                  false),
              new ColumnModel(
                  "address",
                  unresolvedType("Address"),
                  null,
                  false,
                  null,
                  null,
                  null,
                  null,
                  null,
                  true)),
          List.of(
              new RelationModel(
                  "customer",
                  RelationKind.MANY_TO_ONE,
                  type(P + "Customer"),
                  false,
                  null,
                  false,
                  "LAZY",
                  List.of(),
                  List.of(new JoinColumnModel("customer_id", null, false)),
                  null),
              new RelationModel(
                  "products",
                  RelationKind.MANY_TO_MANY,
                  type(P + "Product"),
                  true,
                  null,
                  null,
                  null,
                  List.of("PERSIST", "MERGE"),
                  List.of(),
                  new JoinTableModel(
                      "order_products",
                      List.of(new JoinColumnModel("order_id", null, null)),
                      List.of(new JoinColumnModel("product_id", null, null)))),
              new RelationModel(
                  "items",
                  RelationKind.ONE_TO_MANY,
                  type(P + "OrderItem"),
                  true,
                  "order",
                  null,
                  null,
                  List.of(),
                  List.of(new JoinColumnModel(null, null, null)),
                  null)));

  @Test
  @DisplayName("The entity list shows kind, names and parent; unstated names are 未指定")
  void listsEntities() {
    var base =
        new EntityModel(
            P + "BaseEntity",
            EntityKind.MAPPED_SUPERCLASS,
            null,
            null,
            null,
            null,
            List.of(),
            List.of());
    var product =
        new EntityModel(
            P + "Product", EntityKind.ENTITY, "Item", null, "sales", null, List.of(), List.of());

    Table table = EntityTables.entityList(List.of(ORDER, product, base));

    assertThat(table.headers()).containsExactly("エンティティ", "種類", "エンティティ名", "テーブル名", "スキーマ", "親クラス");
    assertThat(table.texts())
        .containsExactly(
            List.of("BaseEntity", "MappedSuperclass", "-", "-", "-", "-"),
            List.of("Product", "エンティティ", "Item", "未指定", "sales", "-"),
            List.of("PurchaseOrder", "エンティティ", "未指定", "purchase_orders", "未指定", "BaseEntity"));
  }

  @Test
  @DisplayName("Columns show every SCR-05 item; a missing column name is 未指定, not a guess")
  void listsColumns() {
    Table table = EntityTables.columns(ORDER);

    assertThat(table.headers())
        .containsExactly("フィールド", "型", "カラム名", "PK", "生成戦略", "NULL可", "長さ", "一意", "Enum型", "埋め込み");
    assertThat(table.texts())
        .containsExactly(
            List.of("id", "Long", "未指定", "○", "IDENTITY", "-", "-", "-", "-", "-"),
            List.of("code", "String", "order_code", "-", "-", "×", "20", "○", "-", "-"),
            List.of("status", "OrderStatus", "未指定", "-", "-", "-", "-", "-", "STRING", "-"),
            List.of("address", "Address", "未指定", "-", "-", "-", "-", "-", "-", "○"));
    assertThat(table.rows().get(3).get(1).qualifiedName())
        .as("an unresolved type keeps its source text without a qualified name")
        .isNull();
  }

  @Test
  @DisplayName("Relations show owning side, join columns, join table, optional, fetch and cascade")
  void listsRelations() {
    Table table = EntityTables.relations(ORDER);

    assertThat(table.headers())
        .containsExactly(
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
    assertThat(table.texts())
        .containsExactly(
            List.of(
                "customer",
                "ManyToOne",
                "Customer",
                "○",
                "-",
                "customer_id",
                "-",
                "×",
                "LAZY",
                "-"),
            List.of(
                "products",
                "ManyToMany",
                "Product",
                "○",
                "-",
                "-",
                "order_products（order_id / 逆側 product_id）",
                "-",
                "-",
                "PERSIST, MERGE"),
            List.of("items", "OneToMany", "OrderItem", "×", "order", "未指定", "-", "-", "-", "-"));
  }
}
