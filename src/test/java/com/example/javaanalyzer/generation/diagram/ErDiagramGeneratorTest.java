package com.example.javaanalyzer.generation.diagram;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.generation.Diagram;
import com.example.javaanalyzer.ir.ColumnModel;
import com.example.javaanalyzer.ir.EntityKind;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.JoinColumnModel;
import com.example.javaanalyzer.ir.JoinTableModel;
import com.example.javaanalyzer.ir.RelationKind;
import com.example.javaanalyzer.ir.RelationModel;
import com.example.javaanalyzer.ir.TypeRef;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ErDiagramGeneratorTest {

  private static final String P = "com.example.shop.domain.";

  private static TypeRef type(String qualifiedName) {
    return new TypeRef(
        qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1), qualifiedName, List.of());
  }

  private static ColumnModel column(String field, String typeName) {
    return new ColumnModel(field, type(typeName), null, false, null, null, null, null, null, false);
  }

  private static RelationModel relation(
      String field, RelationKind kind, TypeRef target, String mappedBy, Boolean optional) {
    return new RelationModel(
        field, kind, target, false, mappedBy, optional, null, List.of(), List.of(), null);
  }

  private static EntityModel entity(
      String name, String table, List<ColumnModel> columns, List<RelationModel> relations) {
    return new EntityModel(
        P + name, EntityKind.ENTITY, null, table, null, null, columns, relations);
  }

  /** The entities of the lombok-jpa-demo sample project, as the analysis records them. */
  private static List<EntityModel> shop() {
    return List.of(
        new EntityModel(
            P + "BaseEntity",
            EntityKind.MAPPED_SUPERCLASS,
            null,
            null,
            null,
            null,
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
                    false)),
            List.of()),
        new EntityModel(
            P + "Address",
            EntityKind.EMBEDDABLE,
            null,
            null,
            null,
            null,
            List.of(column("street", "java.lang.String")),
            List.of()),
        entity(
            "PurchaseOrder",
            "purchase_orders",
            List.of(
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
                    false)),
            List.of(
                relation("customer", RelationKind.MANY_TO_ONE, type(P + "Customer"), null, false),
                relation("products", RelationKind.MANY_TO_MANY, type(P + "Product"), null, null))),
        entity(
            "Customer",
            "customers",
            List.of(
                column("name", "java.lang.String"),
                new ColumnModel(
                    "address",
                    type(P + "Address"),
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true)),
            List.of(
                relation(
                    "orders",
                    RelationKind.ONE_TO_MANY,
                    type(P + "PurchaseOrder"),
                    "customer",
                    null))),
        entity(
            "Product",
            null,
            List.of(column("price", "java.math.BigDecimal")),
            List.of(
                relation(
                    "orders",
                    RelationKind.MANY_TO_MANY,
                    type(P + "PurchaseOrder"),
                    "products",
                    null))));
  }

  @Test
  @DisplayName(
      "Draws ENTITY types only, pairs bidirectional relations by mappedBy and shows multiplicity")
  void drawsSampleShop() {
    Diagram diagram = ErDiagramGenerator.diagram(shop()).orElseThrow();

    assertThat(diagram.id()).isEqualTo("er");
    assertThat(diagram.code())
        .isEqualTo(
            """
            erDiagram
              e1["Customer（customers）"] {
                String name
                Address address "埋め込み"
              }
              e2["Product（テーブル名未指定）"] {
                BigDecimal price
              }
              e3["PurchaseOrder（purchase_orders）"] {
                OrderStatus status "Enum: STRING"
              }
              e1 ||--o{ e3 : "orders / customer"
              e3 }o--o{ e2 : "products / orders"
            """);
  }

  @Test
  @DisplayName("A primary key column is marked PK")
  void marksPrimaryKey() {
    var withId =
        entity(
            "Tag",
            "tags",
            List.of(
                new ColumnModel(
                    "id",
                    type("java.lang.Long"),
                    "tag_id",
                    true,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false)),
            List.of());

    assertThat(ErDiagramGenerator.diagram(List.of(withId)).orElseThrow().code())
        .contains("    Long id PK\n");
  }

  @Test
  @DisplayName("Unidirectional relations get their own line; optional=false means exactly one")
  void unidirectionalMultiplicity() {
    var order =
        entity(
            "Order",
            null,
            List.of(),
            List.of(
                relation("buyer", RelationKind.MANY_TO_ONE, type(P + "Customer"), null, null),
                relation("seller", RelationKind.MANY_TO_ONE, type(P + "Customer"), null, false),
                relation("invoice", RelationKind.ONE_TO_ONE, type(P + "Invoice"), null, false),
                relation("note", RelationKind.ONE_TO_ONE, type(P + "Invoice"), null, null)));
    var customer = entity("Customer", null, List.of(), List.of());
    var invoice = entity("Invoice", null, List.of(), List.of());

    String code =
        ErDiagramGenerator.diagram(List.of(order, customer, invoice)).orElseThrow().code();

    // e1 = Customer, e2 = Invoice, e3 = Order
    assertThat(code)
        .contains("  e1 |o--o{ e3 : \"buyer\"\n")
        .contains("  e1 ||--o{ e3 : \"seller\"\n")
        .contains("  e3 |o--|| e2 : \"invoice\"\n")
        .contains("  e3 |o--o| e2 : \"note\"\n");
  }

  @Test
  @DisplayName("An entity box names its direct superclass as written; none without one")
  void superclassInEntityLabel() {
    var petType =
        new EntityModel(
            P + "PetType",
            EntityKind.ENTITY,
            null,
            "types",
            null,
            new TypeRef("NamedEntity", P + "NamedEntity", List.of()),
            List.of(),
            List.of());
    var owner =
        new EntityModel(
            P + "Owner",
            EntityKind.ENTITY,
            null,
            "owners",
            null,
            new TypeRef("BaseEntity<Long>", P + "BaseEntity", List.of()),
            List.of(column("city", "java.lang.String")),
            List.of());
    var vet = entity("Vet", null, List.of(), List.of());

    String code = ErDiagramGenerator.diagram(List.of(petType, owner, vet)).orElseThrow().code();

    // e1 = Owner, e2 = PetType, e3 = Vet
    assertThat(code)
        .contains("  e1[\"Owner（owners）継承：BaseEntity#lt;Long#gt;\"] {\n")
        .contains("  e2[\"PetType（types）継承：NamedEntity\"]\n")
        .contains("  e3[\"Vet（テーブル名未指定）\"]\n");
  }

  private static RelationModel joined(
      String field,
      RelationKind kind,
      String target,
      List<JoinColumnModel> joinColumns,
      JoinTableModel joinTable) {
    return new RelationModel(
        field, kind, type(P + target), false, null, null, null, List.of(), joinColumns, joinTable);
  }

  @Test
  @DisplayName(
      "Line labels add the join columns or join table stated on the owning side, nothing else")
  void joinColumnsAndTablesInLabels() {
    var order =
        entity(
            "Order",
            null,
            List.of(),
            List.of(
                joined(
                    "customer",
                    RelationKind.MANY_TO_ONE,
                    "Customer",
                    List.of(new JoinColumnModel("customer_id", null, null)),
                    null),
                joined(
                    "products",
                    RelationKind.MANY_TO_MANY,
                    "Product",
                    List.of(),
                    new JoinTableModel("order_products", List.of(), List.of())),
                joined(
                    "shop",
                    RelationKind.MANY_TO_ONE,
                    "Shop",
                    List.of(
                        new JoinColumnModel("shop_code", "code", null),
                        new JoinColumnModel("shop_region", "region", null)),
                    null),
                joined(
                    "coupon",
                    RelationKind.MANY_TO_ONE,
                    "Coupon",
                    List.of(new JoinColumnModel(null, null, false)),
                    new JoinTableModel(null, List.of(), List.of()))));
    var customer =
        entity(
            "Customer",
            null,
            List.of(),
            List.of(
                relation("orders", RelationKind.ONE_TO_MANY, type(P + "Order"), "customer", null)));
    var product = entity("Product", null, List.of(), List.of());
    var shop = entity("Shop", null, List.of(), List.of());
    var coupon = entity("Coupon", null, List.of(), List.of());

    String code =
        ErDiagramGenerator.diagram(List.of(order, customer, product, shop, coupon))
            .orElseThrow()
            .code();

    // e1 = Coupon, e2 = Customer, e3 = Order, e4 = Product, e5 = Shop
    assertThat(code)
        .contains("  e2 |o--o{ e3 : \"orders / customer（customer_id）\"\n")
        .contains("  e3 }o--o{ e4 : \"products（order_products）\"\n")
        .contains("  e5 |o--o{ e3 : \"shop（shop_code, shop_region）\"\n")
        .contains("  e1 |o--o{ e3 : \"coupon\"\n");
  }

  @Test
  @DisplayName("A mappedBy without a matching field is not paired and keeps its own line")
  void unmatchedMappedByIsNotPaired() {
    var customer =
        entity(
            "Customer",
            null,
            List.of(),
            List.of(
                relation("orders", RelationKind.ONE_TO_MANY, type(P + "Order"), "owner", null)));
    var order =
        entity(
            "Order",
            null,
            List.of(),
            List.of(
                relation("customer", RelationKind.MANY_TO_ONE, type(P + "Customer"), null, null)));

    String code = ErDiagramGenerator.diagram(List.of(customer, order)).orElseThrow().code();

    assertThat(code)
        .contains("  e1 |o--o{ e2 : \"orders\"\n")
        .contains("  e1 |o--o{ e2 : \"customer\"\n");
  }

  @Test
  @DisplayName(
      "A target that is not a drawn entity is still drawn, labeled as outside the entities")
  void targetOutsideEntitiesIsKept() {
    var order =
        entity(
            "Order",
            null,
            List.of(),
            List.of(
                relation(
                    "owner",
                    RelationKind.MANY_TO_ONE,
                    new TypeRef("Owner", null, List.of()),
                    null,
                    null)));

    String code = ErDiagramGenerator.diagram(List.of(order)).orElseThrow().code();

    assertThat(code)
        .contains("  x1[\"Owner（エンティティ外）\"]\n")
        .contains("  x1 |o--o{ e1 : \"owner\"\n");
  }

  @Test
  @DisplayName(
      "Per-package ER diagrams draw each package's entities; other packages' entities are outside")
  void perPackageDiagrams() {
    String order = "com.example.shop.order.";
    String customer = "com.example.shop.customer.";
    var orderEntity =
        new EntityModel(
            order + "PurchaseOrder",
            EntityKind.ENTITY,
            null,
            "orders",
            null,
            null,
            List.of(),
            List.of(
                relation(
                    "customer", RelationKind.MANY_TO_ONE, type(customer + "Customer"), null, false),
                relation(
                    "lines", RelationKind.ONE_TO_MANY, type(order + "OrderLine"), "order", null)));
    var lineEntity =
        new EntityModel(
            order + "OrderLine",
            EntityKind.ENTITY,
            null,
            null,
            null,
            null,
            List.of(),
            List.of(
                relation(
                    "order", RelationKind.MANY_TO_ONE, type(order + "PurchaseOrder"), null, null)));
    var customerEntity =
        new EntityModel(
            customer + "Customer",
            EntityKind.ENTITY,
            null,
            "customers",
            null,
            null,
            List.of(),
            List.of());

    List<Diagram> diagrams =
        ErDiagramGenerator.diagramsPerPackage(
            List.of(orderEntity, lineEntity, customerEntity), "com.example.shop");

    assertThat(diagrams)
        .extracting(Diagram::id, Diagram::title)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("er-1", "ER図（customer）"),
            org.assertj.core.groups.Tuple.tuple("er-2", "ER図（order）"));
    assertThat(diagrams.get(1).code())
        .isEqualTo(
            """
            erDiagram
              e1["OrderLine（テーブル名未指定）"]
              e2["PurchaseOrder（orders）"]
              x1["Customer（他パッケージ）"]
              e2 |o--o{ e1 : "lines / order"
              x1 ||--o{ e2 : "customer"
            """);
    assertThat(diagrams.get(0).code()).doesNotContain("PurchaseOrder");
  }

  @Test
  @DisplayName(
      "Japanese field and type names keep a valid attribute and the original in the comment")
  void japaneseIdentifiersAreKeptInComment() {
    var customer =
        entity(
            "Customer",
            null,
            List.of(
                new ColumnModel(
                    "顧客名",
                    new TypeRef("氏名", P + "氏名", List.of()),
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true)),
            List.of());

    assertThat(ErDiagramGenerator.diagram(List.of(customer)).orElseThrow().code())
        .contains("    __ ___ \"型: 氏名, フィールド: 顧客名, 埋め込み\"\n");
  }

  @Test
  @DisplayName("Names with Mermaid syntax characters are escaped; no ENTITY means no diagram")
  void escapesAndEmpty() {
    var weird =
        entity("Weird$Inner", "t\"x", List.of(column("values", "java.util.List")), List.of());

    assertThat(ErDiagramGenerator.diagram(List.of(weird)).orElseThrow().code())
        .contains("e1[\"Weird$Inner（t#quot;x）\"]");
    assertThat(ErDiagramGenerator.diagram(shop().subList(0, 2))).isEmpty();
  }

  @Test
  @DisplayName("An attribute named pk is prefixed in the diagram and its original name is noted")
  void keyMarkerAttributeNameIsPrefixedAndNoted() {
    EntityModel item =
        entity(
            "OrderItem",
            null,
            List.of(
                new ColumnModel(
                    "pk", type(P + "OrderItemPK"), null, true, null, null, null, null, null, true)),
            List.of());

    String code = ErDiagramGenerator.diagram(List.of(item)).orElseThrow().code();

    assertThat(code).contains("OrderItemPK _pk PK \"フィールド: pk, 埋め込み\"");
  }

  @Test
  @DisplayName("Entities of the default package get a named per-package diagram title")
  void defaultPackageHasNamedTitle() {
    EntityModel order =
        new EntityModel("Order", EntityKind.ENTITY, null, null, null, null, List.of(), List.of());

    List<Diagram> diagrams = ErDiagramGenerator.diagramsPerPackage(List.of(order), "");

    assertThat(diagrams).extracting(Diagram::title).containsExactly("ER図（デフォルトパッケージ）");
  }
}
