package com.example.javaanalyzer.generation;

import static com.example.javaanalyzer.generation.IrFixtures.component;
import static com.example.javaanalyzer.generation.IrFixtures.project;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.ir.ComponentRole;
import com.example.javaanalyzer.ir.EntityKind;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.RepositoryModel;
import com.example.javaanalyzer.ir.TypeKind;
import com.example.javaanalyzer.ir.TypeModel;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClassListGeneratorTest {

  private static TypeModel type(String qualifiedName, TypeKind kind, String javadoc) {
    int dot = qualifiedName.lastIndexOf('.');
    return new TypeModel(
        qualifiedName,
        qualifiedName.substring(dot + 1),
        qualifiedName.substring(0, dot),
        kind,
        null,
        List.of(),
        List.of(),
        javadoc,
        null,
        null,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static ProjectModel shop() {
    return project()
        .types(
            type("com.example.web.OrderController", TypeKind.CLASS, null),
            type("com.example.domain.Order", TypeKind.CLASS, "\n  An order.\n  Second line.\n"),
            type("com.example.repo.OrderRepository", TypeKind.INTERFACE, null),
            type("com.example.domain.Status", TypeKind.ENUM, null))
        .components(component("com.example.web.OrderController", ComponentRole.CONTROLLER))
        .repositories(new RepositoryModel("com.example.repo.OrderRepository", null, null, null))
        .entities(
            new EntityModel(
                "com.example.domain.Order",
                EntityKind.ENTITY,
                null,
                null,
                null,
                null,
                List.of(),
                List.of()))
        .build();
  }

  @Test
  @DisplayName("Lists every type with kind, role, package and the first Javadoc line, sorted")
  void listsTypes() {
    Table table = ClassListGenerator.table(shop(), null, null);

    assertThat(table.headers()).containsExactly("クラス名", "種類", "役割", "パッケージ", "概要");
    assertThat(table.texts())
        .containsExactly(
            List.of("Order", "クラス", "Entity", "com.example.domain", "An order."),
            List.of("Status", "Enum", "-", "com.example.domain", "-"),
            List.of("OrderRepository", "インタフェース", "Repository", "com.example.repo", "-"),
            List.of("OrderController", "クラス", "Controller", "com.example.web", "-"));
  }

  @Test
  @DisplayName("Can be narrowed by package and by role")
  void filters() {
    assertThat(ClassListGenerator.table(shop(), "com.example.domain", null).texts())
        .extracting(row -> row.get(0))
        .containsExactly("Order", "Status");
    assertThat(ClassListGenerator.table(shop(), null, "Repository").texts())
        .extracting(row -> row.get(0))
        .containsExactly("OrderRepository");
  }

  @Test
  @DisplayName("Offers the roles present in the project as filter choices")
  void roleChoices() {
    assertThat(ClassListGenerator.roles(shop()))
        .containsExactly("Controller", "Repository", "Entity");
  }
}
