package com.example.javaanalyzer.generation;

import static com.example.javaanalyzer.generation.IrFixtures.project;
import static com.example.javaanalyzer.generation.IrFixtures.type;
import static com.example.javaanalyzer.generation.IrFixtures.unresolvedType;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.TypeRef;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApiListGeneratorTest {

  private static final String ORDERS = "com.example.web.OrderController";
  private static final String ITEMS = "com.example.web.ItemController";

  private static EndpointModel endpoint(
      String controller,
      String method,
      List<String> httpMethods,
      List<String> paths,
      boolean pathsResolved,
      TypeRef request,
      TypeRef response) {
    return new EndpointModel(
        controller,
        method,
        httpMethods,
        paths,
        pathsResolved,
        request,
        response,
        response,
        List.of(),
        true,
        List.of());
  }

  @Test
  @DisplayName("Lists exactly the six FR-06 items, one row per endpoint")
  void listsSixItems() {
    var model =
        project()
            .endpoints(
                endpoint(
                    ORDERS,
                    "get",
                    List.of("GET"),
                    List.of("/api/orders/{id}"),
                    true,
                    null,
                    type("com.example.web.dto.OrderResponse")),
                endpoint(
                    ORDERS,
                    "create",
                    List.of("POST"),
                    List.of("/api/orders"),
                    true,
                    type("com.example.web.dto.CreateOrderRequest"),
                    type("com.example.web.dto.OrderResponse")))
            .build();

    Table table = ApiListGenerator.table(model, null);

    assertThat(table.headers())
        .containsExactly("Controller", "Method", "HTTP", "Path", "Request DTO", "Response DTO");
    assertThat(table.texts())
        .containsExactly(
            List.of(
                "OrderController",
                "create",
                "POST",
                "/api/orders",
                "CreateOrderRequest",
                "OrderResponse"),
            List.of("OrderController", "get", "GET", "/api/orders/{id}", "-", "OrderResponse"));
    assertThat(table.rows().get(0).get(0).qualifiedName()).isEqualTo(ORDERS);
  }

  @Test
  @DisplayName("Several paths give one row each; no HTTP method means all methods")
  void multiplePathsAndAllMethods() {
    var model =
        project()
            .endpoints(
                endpoint(
                    ITEMS, "list", List.of(), List.of("/items", "/products"), true, null, null))
            .build();

    assertThat(ApiListGenerator.table(model, null).texts())
        .containsExactly(
            List.of("ItemController", "list", "全メソッド", "/items", "-", "-"),
            List.of("ItemController", "list", "全メソッド", "/products", "-", "-"));
  }

  @Test
  @DisplayName("Unresolved paths are shown as written with a note, never dropped")
  void unresolvedPathsAreNoted() {
    var model =
        project()
            .endpoints(
                endpoint(
                    ITEMS,
                    "find",
                    List.of("GET", "HEAD"),
                    List.of("ApiPaths.ITEMS + \"/{id}\""),
                    false,
                    null,
                    unresolvedType("ItemDto")))
            .build();

    assertThat(ApiListGenerator.table(model, null).texts())
        .containsExactly(
            List.of(
                "ItemController",
                "find",
                "GET, HEAD",
                "ApiPaths.ITEMS + \"/{id}\"（未解決）",
                "-",
                "ItemDto"));
  }

  @Test
  @DisplayName("Can be narrowed to one controller, and an empty project gives an empty table")
  void filtersByControllerAndHandlesEmpty() {
    var model =
        project()
            .endpoints(
                endpoint(ORDERS, "get", List.of("GET"), List.of("/o"), true, null, null),
                endpoint(ITEMS, "get", List.of("GET"), List.of("/i"), true, null, null))
            .build();

    assertThat(ApiListGenerator.table(model, ITEMS).texts())
        .extracting(row -> row.get(3))
        .containsExactly("/i");
    assertThat(ApiListGenerator.table(project().build(), null).isEmpty()).isTrue();
  }

  @Test
  @DisplayName("Controllers are sorted by name, then rows by path and method name")
  void sortsDeterministically() {
    var model =
        project()
            .endpoints(
                endpoint(ORDERS, "b", List.of("GET"), List.of("/z"), true, null, null),
                endpoint(ITEMS, "a", List.of("GET"), List.of("/y"), true, null, null),
                endpoint(ORDERS, "a", List.of("GET"), List.of("/a"), true, null, null))
            .build();

    assertThat(ApiListGenerator.table(model, null).texts())
        .extracting(row -> row.get(0) + "." + row.get(1))
        .containsExactly("ItemController.a", "OrderController.a", "OrderController.b");
  }
}
