package com.example.javaanalyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TypeNameResolverTest {

  private static final Set<String> PROJECT_TYPES =
      Set.of(
          "com.example.order.Order",
          "com.example.order.Order.Line",
          "com.example.order.OrderStatus",
          "com.example.user.User",
          "com.example.common.Money");

  private static TypeNameResolver resolverFor(String source, String... declaredInFile) {
    CompilationUnit unit = StaticJavaParser.parse(source);
    return new TypeNameResolver(unit, List.of(declaredInFile), PROJECT_TYPES);
  }

  private static final String ORDER_SOURCE =
      """
      package com.example.order;
      import com.example.user.User;
      import java.util.List;
      import com.example.common.*;
      import jakarta.persistence.*;
      import static java.util.Objects.requireNonNull;
      class Order { static class Line {} }
      """;

  private final TypeNameResolver resolver =
      resolverFor(ORDER_SOURCE, "com.example.order.Order", "com.example.order.Order.Line");

  @Test
  @DisplayName("Single-type imports resolve to the imported name, even for external libraries")
  void resolvesSingleTypeImports() {
    assertThat(resolver.resolveName("User")).isEqualTo("com.example.user.User");
    assertThat(resolver.resolveName("List")).isEqualTo("java.util.List");
  }

  @Test
  @DisplayName("Same-package project types and on-demand imports of project packages resolve")
  void resolvesSamePackageAndProjectWildcard() {
    assertThat(resolver.resolveName("OrderStatus")).isEqualTo("com.example.order.OrderStatus");
    assertThat(resolver.resolveName("Money")).isEqualTo("com.example.common.Money");
  }

  @Test
  @DisplayName("Types declared in the file, including nested ones, resolve by simple name")
  void resolvesDeclaredAndNestedTypes() {
    assertThat(resolver.resolveName("Order")).isEqualTo("com.example.order.Order");
    assertThat(resolver.resolveName("Line")).isEqualTo("com.example.order.Order.Line");
    assertThat(resolver.resolveName("Order.Line")).isEqualTo("com.example.order.Order.Line");
  }

  @Test
  @DisplayName("Primitives, well-known java.lang types and qualified names written in source")
  void resolvesPrimitivesJavaLangAndQualifiedNames() {
    assertThat(resolver.resolveName("int")).isEqualTo("int");
    assertThat(resolver.resolveName("String")).isEqualTo("java.lang.String");
    assertThat(resolver.resolveName("Long")).isEqualTo("java.lang.Long");
    assertThat(resolver.resolveName("java.time.LocalDate")).isEqualTo("java.time.LocalDate");
  }

  @Test
  @DisplayName("A type only reachable through an external on-demand import stays unresolved")
  void leavesExternalWildcardTypesUnresolved() {
    assertThat(resolver.resolveName("Entity")).isNull();
    assertThat(resolver.resolveName("Unknown")).isNull();
    assertThat(resolver.resolveName("Unknown.Nested")).isNull();
  }

  @Test
  @DisplayName("refersTo accepts a simple name whose package is imported on demand")
  void refersToThroughOnDemandImport() {
    assertThat(resolver.refersTo("Entity", "jakarta.persistence.Entity")).isTrue();
    assertThat(resolver.refersTo("Entity", "javax.persistence.Entity")).isFalse();
    assertThat(resolver.refersTo("jakarta.persistence.Entity", "jakarta.persistence.Entity"))
        .isTrue();
  }

  @Test
  @DisplayName("refersTo is false when the name resolves to a different (project) type")
  void refersToRejectsShadowingProjectType() {
    TypeNameResolver shadowing =
        resolverFor(
            """
            package com.example.common;
            import jakarta.persistence.*;
            class Money {}
            """,
            "com.example.common.Money");

    assertThat(shadowing.refersTo("Money", "jakarta.persistence.Money")).isFalse();
    assertThat(shadowing.refersTo("Money", "com.example.common.Money")).isTrue();
  }

  @Test
  @DisplayName("Static imports are not used for type resolution")
  void ignoresStaticImports() {
    assertThat(resolver.resolveName("requireNonNull")).isNull();
  }

  private static final String NESTED_DATA_SOURCE =
      """
      package com.example.dto;
      import lombok.Data;
      import lombok.Value;
      @Data
      public class ApiResponse {
        private Data data;
        @lombok.Getter public static class Data { private Value value; }
        public static class Other { private Value value; private Data data; }
        static class Holder { static class Value {} }
      }
      """;

  private static final String[] NESTED_DATA_TYPES = {
    "com.example.dto.ApiResponse",
    "com.example.dto.ApiResponse.Data",
    "com.example.dto.ApiResponse.Other",
    "com.example.dto.ApiResponse.Holder",
    "com.example.dto.ApiResponse.Holder.Value"
  };

  private static TypeNameResolver nestedDataResolver(CompilationUnit unit) {
    return new TypeNameResolver(
        unit, List.of(NESTED_DATA_TYPES), Set.copyOf(List.of(NESTED_DATA_TYPES)));
  }

  @Test
  @DisplayName(
      "A type's own annotation is outside its body, so a nested type does not shadow the import")
  void ownAnnotationIgnoresMemberTypes() {
    CompilationUnit unit = StaticJavaParser.parse(NESTED_DATA_SOURCE);
    TypeNameResolver fileResolver = nestedDataResolver(unit);
    ClassOrInterfaceDeclaration apiResponse = unit.getClassByName("ApiResponse").orElseThrow();
    AnnotationExpr dataAnnotation = apiResponse.getAnnotation(0);

    assertThat(fileResolver.scopedTo(dataAnnotation).resolveName("Data")).isEqualTo("lombok.Data");
  }

  @Test
  @DisplayName("Inside the body, a member type shadows a single-type import of the same name")
  void memberTypeShadowsImportInsideBody() {
    CompilationUnit unit = StaticJavaParser.parse(NESTED_DATA_SOURCE);
    TypeNameResolver fileResolver = nestedDataResolver(unit);
    FieldDeclaration dataField =
        unit.getClassByName("ApiResponse").orElseThrow().getFieldByName("data").orElseThrow();

    assertThat(fileResolver.scopedTo(dataField.getElementType()).resolveName("Data"))
        .isEqualTo("com.example.dto.ApiResponse.Data");
  }

  @Test
  @DisplayName(
      "Inside a nested type, the enclosing type's members are in scope, but not a sibling's")
  void siblingMemberTypesAreNotInScope() {
    CompilationUnit unit = StaticJavaParser.parse(NESTED_DATA_SOURCE);
    TypeNameResolver fileResolver = nestedDataResolver(unit);
    ClassOrInterfaceDeclaration other =
        unit.findFirst(ClassOrInterfaceDeclaration.class, c -> c.getNameAsString().equals("Other"))
            .orElseThrow();
    TypeNameResolver inOther =
        fileResolver.scopedTo(other.getFieldByName("value").orElseThrow().getElementType());

    assertThat(inOther.resolveName("Value")).isEqualTo("lombok.Value");
    assertThat(inOther.resolveName("Data")).isEqualTo("com.example.dto.ApiResponse.Data");
  }
}
