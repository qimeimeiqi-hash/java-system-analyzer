package com.example.javaanalyzer.analysis.plugin.springboot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.example.javaanalyzer.analysis.AnalysisContext;
import com.example.javaanalyzer.analysis.AnalysisTestSupport;
import com.example.javaanalyzer.analysis.ProjectAnalyzer;
import com.example.javaanalyzer.ir.AnalysisIssue;
import com.example.javaanalyzer.ir.ColumnModel;
import com.example.javaanalyzer.ir.ComponentModel;
import com.example.javaanalyzer.ir.ComponentRole;
import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.EntityKind;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.InjectionKind;
import com.example.javaanalyzer.ir.InjectionModel;
import com.example.javaanalyzer.ir.IssueKind;
import com.example.javaanalyzer.ir.MemberOrigin;
import com.example.javaanalyzer.ir.MethodModel;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.RelationKind;
import com.example.javaanalyzer.ir.RelationModel;
import com.example.javaanalyzer.ir.RepositoryModel;
import com.example.javaanalyzer.ir.TypeModel;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * No.9 completion check on {@code sample-projects/lombok-jpa-demo}: class/method lists (incl.
 * Lombok), annotations, JPA associations, and errors for a syntax error and an unresolved import.
 */
class SampleProjectAnalysisTest {

  private static final String SHOP = "com.example.shop.";
  private static ProjectModel model;

  @BeforeAll
  static void analyzeSampleProject() {
    Path projectRoot = Path.of("src/test/resources/sample-projects/lombok-jpa-demo");
    model = new ProjectAnalyzer(List.of(new SpringBootPlugin())).analyze(projectRoot);
  }

  private static TypeModel type(String name) {
    return model.types().stream()
        .filter(t -> t.qualifiedName().equals(SHOP + name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No type " + name));
  }

  private static EntityModel entity(String name) {
    return model.entities().stream()
        .filter(e -> e.typeName().equals(SHOP + name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No entity " + name));
  }

  private static ComponentModel component(String name) {
    return model.components().stream()
        .filter(c -> c.typeName().equals(SHOP + name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No component " + name));
  }

  private static List<String> lombokMethods(TypeModel type) {
    return type.methods().stream()
        .filter(m -> m.origin() == MemberOrigin.LOMBOK)
        .map(MethodModel::name)
        .toList();
  }

  @Test
  @DisplayName("Class list: every parsable main type, excluding the broken file and test sources")
  void listsClasses() {
    assertThat(model.rootPackage()).isEqualTo("com.example.shop");
    assertThat(model.types())
        .extracting(TypeModel::qualifiedName)
        .containsExactlyInAnyOrder(
            SHOP + "ShopApplication",
            SHOP + "domain.Address",
            SHOP + "domain.BaseEntity",
            SHOP + "domain.Customer",
            SHOP + "domain.OrderStatus",
            SHOP + "domain.Product",
            SHOP + "domain.PurchaseOrder",
            SHOP + "repository.CustomerRepository",
            SHOP + "repository.PurchaseOrderRepository",
            SHOP + "service.LegacyReportService",
            SHOP + "service.NotificationService",
            SHOP + "service.OrderService",
            SHOP + "web.OrderController",
            SHOP + "web.dto.CreateOrderRequest",
            SHOP + "web.dto.OrderResponse");
  }

  @Test
  @DisplayName("Method list includes source methods and Lombok-generated accessors")
  void listsMethodsIncludingLombok() {
    assertThat(type("service.OrderService").methods())
        .extracting(MethodModel::name, MethodModel::origin)
        .containsExactly(
            tuple("placeOrder", MemberOrigin.SOURCE), tuple("find", MemberOrigin.SOURCE));
    assertThat(lombokMethods(type("domain.Customer")))
        .containsExactlyInAnyOrder(
            "getName",
            "getEmail",
            "isActive",
            "getAddress",
            "getOrders",
            "getDisplayName",
            "setName",
            "setEmail",
            "setActive",
            "setAddress",
            "setOrders",
            "setDisplayName");
    assertThat(lombokMethods(type("domain.PurchaseOrder")))
        .contains("getCustomer", "setCustomer", "getStatus", "toString", "equals", "hashCode");
    assertThat(lombokMethods(type("web.dto.OrderResponse")))
        .containsExactlyInAnyOrder("getId", "getStatus", "toString", "equals", "hashCode");
  }

  @Test
  @DisplayName("Lombok constructors: @RequiredArgsConstructor, @NoArgsConstructor, @Value, @Data")
  void infersLombokConstructors() {
    assertThat(type("service.OrderService").constructors())
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.origin()).isEqualTo(MemberOrigin.LOMBOK);
              assertThat(c.generatedBy()).isEqualTo("RequiredArgsConstructor");
              assertThat(c.parameters())
                  .extracting(p -> p.name())
                  .containsExactly("purchaseOrderRepository", "customerRepository");
            });
    assertThat(type("domain.Customer").constructors())
        .singleElement()
        .satisfies(c -> assertThat(c.parameters()).isEmpty());
    assertThat(type("web.dto.OrderResponse").constructors())
        .singleElement()
        .satisfies(
            c ->
                assertThat(c.parameters())
                    .extracting(p -> p.name())
                    .containsExactly("id", "status"));
  }

  @Test
  @DisplayName("Annotations are kept with resolved names and raw attributes")
  void keepsAnnotations() {
    assertThat(type("domain.Customer").annotations())
        .extracting(a -> a.name())
        .containsExactly("Entity", "Table", "Getter", "Setter", "NoArgsConstructor");
    assertThat(type("domain.PurchaseOrder").annotations())
        .filteredOn(a -> a.name().equals("Table"))
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.qualifiedName()).isEqualTo("jakarta.persistence.Table");
              assertThat(a.attributes()).containsEntry("name", "\"purchase_orders\"");
            });
  }

  @Test
  @DisplayName(
      "Annotations from wildcard imports (jakarta.persistence.*, web.bind.annotation.*) are qualified in the IR")
  void qualifiesWildcardImportedAnnotations() {
    assertThat(type("domain.Customer").annotations())
        .extracting(a -> a.name(), a -> a.qualifiedName())
        .contains(
            tuple("Entity", "jakarta.persistence.Entity"),
            tuple("Table", "jakarta.persistence.Table"),
            tuple("Getter", "lombok.Getter"));
    assertThat(type("domain.Customer").fields().get(0).annotations())
        .extracting(a -> a.qualifiedName())
        .containsExactly("jakarta.persistence.Column");
    assertThat(type("web.OrderController").methods().get(0).annotations())
        .extracting(a -> a.qualifiedName())
        .containsExactly("org.springframework.web.bind.annotation.GetMapping");
    assertThat(type("web.OrderController").methods().get(0).parameters().get(0).annotations())
        .extracting(a -> a.qualifiedName())
        .containsExactly("org.springframework.web.bind.annotation.PathVariable");
  }

  @Test
  @DisplayName("Components and DI, including injection through Lombok constructors (FR-08 input)")
  void detectsComponentsAndInjections() {
    assertThat(model.components())
        .extracting(ComponentModel::typeName, ComponentModel::role)
        .containsExactlyInAnyOrder(
            tuple(SHOP + "web.OrderController", ComponentRole.CONTROLLER),
            tuple(SHOP + "service.OrderService", ComponentRole.SERVICE),
            tuple(SHOP + "service.NotificationService", ComponentRole.SERVICE),
            tuple(SHOP + "service.LegacyReportService", ComponentRole.SERVICE),
            tuple(SHOP + "repository.CustomerRepository", ComponentRole.REPOSITORY),
            tuple(SHOP + "repository.PurchaseOrderRepository", ComponentRole.REPOSITORY));
    assertThat(component("web.OrderController").injections())
        .extracting(
            InjectionModel::kind,
            InjectionModel::memberName,
            i -> i.type().qualifiedName(),
            InjectionModel::origin)
        .containsExactly(
            tuple(
                InjectionKind.CONSTRUCTOR,
                "orderService",
                SHOP + "service.OrderService",
                MemberOrigin.LOMBOK));
    assertThat(component("service.OrderService").injections())
        .extracting(i -> i.type().qualifiedName())
        .containsExactly(
            SHOP + "repository.PurchaseOrderRepository", SHOP + "repository.CustomerRepository");
    assertThat(component("service.NotificationService").injections())
        .extracting(InjectionModel::kind, InjectionModel::memberName)
        .containsExactly(tuple(InjectionKind.FIELD, "mailSender"));
  }

  @Test
  @DisplayName("Endpoints with the six FR-06 items")
  void extractsEndpoints() {
    assertThat(model.endpoints())
        .extracting(
            EndpointModel::controllerType,
            EndpointModel::methodName,
            EndpointModel::httpMethods,
            EndpointModel::paths,
            e -> e.requestBodyType() == null ? null : e.requestBodyType().qualifiedName(),
            e -> e.responseType().qualifiedName())
        .containsExactly(
            tuple(
                SHOP + "web.OrderController",
                "get",
                List.of("GET"),
                List.of("/api/orders/{id}"),
                null,
                SHOP + "web.dto.OrderResponse"),
            tuple(
                SHOP + "web.OrderController",
                "create",
                List.of("POST"),
                List.of("/api/orders"),
                SHOP + "web.dto.CreateOrderRequest",
                SHOP + "web.dto.OrderResponse"));
  }

  @Test
  @DisplayName("Entities: kinds, explicit table names only, columns, @Transient skipped")
  void extractsEntities() {
    assertThat(model.entities())
        .extracting(EntityModel::typeName, EntityModel::kind, EntityModel::tableName)
        .containsExactlyInAnyOrder(
            tuple(SHOP + "domain.BaseEntity", EntityKind.MAPPED_SUPERCLASS, null),
            tuple(SHOP + "domain.Address", EntityKind.EMBEDDABLE, null),
            tuple(SHOP + "domain.Customer", EntityKind.ENTITY, "customers"),
            tuple(SHOP + "domain.PurchaseOrder", EntityKind.ENTITY, "purchase_orders"),
            tuple(SHOP + "domain.Product", EntityKind.ENTITY, null));
    assertThat(entity("domain.BaseEntity").columns())
        .extracting(
            ColumnModel::fieldName,
            ColumnModel::id,
            ColumnModel::generationStrategy,
            ColumnModel::columnName)
        .containsExactly(
            tuple("id", true, "IDENTITY", null), tuple("createdAt", false, null, "created_at"));
    assertThat(entity("domain.Customer").superType().qualifiedName())
        .isEqualTo(SHOP + "domain.BaseEntity");
    assertThat(entity("domain.Customer").columns())
        .extracting(
            ColumnModel::fieldName,
            ColumnModel::nullable,
            ColumnModel::length,
            ColumnModel::unique,
            ColumnModel::embedded)
        .containsExactly(
            tuple("name", false, 100, null, false),
            tuple("email", null, null, true, false),
            tuple("active", null, null, null, false),
            tuple("address", null, null, null, true));
  }

  @Test
  @DisplayName("Associations with multiplicity, owning side, join column and join table (FR-07)")
  void extractsAssociations() {
    assertThat(entity("domain.Customer").relations())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.kind()).isEqualTo(RelationKind.ONE_TO_MANY);
              assertThat(r.targetType().qualifiedName()).isEqualTo(SHOP + "domain.PurchaseOrder");
              assertThat(r.mappedBy()).isEqualTo("customer");
              assertThat(r.owningSide()).isFalse();
              assertThat(r.cascade()).containsExactly("ALL");
            });
    List<RelationModel> orderRelations = entity("domain.PurchaseOrder").relations();
    assertThat(orderRelations)
        .extracting(
            RelationModel::fieldName,
            RelationModel::kind,
            r -> r.targetType().qualifiedName(),
            RelationModel::owningSide)
        .containsExactly(
            tuple("customer", RelationKind.MANY_TO_ONE, SHOP + "domain.Customer", true),
            tuple("products", RelationKind.MANY_TO_MANY, SHOP + "domain.Product", true));
    RelationModel customer = orderRelations.get(0);
    assertThat(customer.optional()).isFalse();
    assertThat(customer.fetch()).isEqualTo("LAZY");
    assertThat(customer.joinColumns())
        .extracting(j -> j.name(), j -> j.nullable())
        .containsExactly(tuple("customer_id", false));
    RelationModel products = orderRelations.get(1);
    assertThat(products.joinTable().name()).isEqualTo("order_products");
    assertThat(products.joinTable().joinColumns())
        .extracting(j -> j.name())
        .containsExactly("order_id");
    assertThat(products.joinTable().inverseJoinColumns())
        .extracting(j -> j.name())
        .containsExactly("product_id");
    assertThat(entity("domain.Product").relations())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.kind()).isEqualTo(RelationKind.MANY_TO_MANY);
              assertThat(r.mappedBy()).isEqualTo("products");
            });
  }

  @Test
  @DisplayName("Spring Data repositories are bound to their entity and id types")
  void extractsRepositories() {
    assertThat(model.repositories())
        .extracting(
            RepositoryModel::typeName,
            r -> r.entityType().qualifiedName(),
            r -> r.idType().qualifiedName())
        .containsExactlyInAnyOrder(
            tuple(
                SHOP + "repository.CustomerRepository", SHOP + "domain.Customer", "java.lang.Long"),
            tuple(
                SHOP + "repository.PurchaseOrderRepository",
                SHOP + "domain.PurchaseOrder",
                "java.lang.Long"));
  }

  @Test
  @DisplayName(
      "Error report: the syntax error and the unresolved project import are recorded (FR-14)")
  void reportsErrors() {
    assertThat(model.issues())
        .extracting(AnalysisIssue::kind, AnalysisIssue::file)
        .containsExactlyInAnyOrder(
            tuple(IssueKind.SYNTAX_ERROR, "src/main/java/com/example/shop/util/BrokenUtil.java"),
            tuple(
                IssueKind.UNRESOLVED_IMPORT,
                "src/main/java/com/example/shop/service/LegacyReportService.java"));
  }

  @Test
  @DisplayName("The plugin does not apply to a project without Spring, JPA or Lombok imports")
  void pluginSkipsNonSpringProject(@TempDir Path plainProject) {
    AnalysisContext context =
        AnalysisTestSupport.contextOf(
            plainProject,
            """
            package com.example.plain;
            import java.util.List;
            public class Plain { List<String> names; }
            """);

    assertThat(new SpringBootPlugin().supports(context)).isFalse();
  }
}
