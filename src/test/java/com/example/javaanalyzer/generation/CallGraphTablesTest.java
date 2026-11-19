package com.example.javaanalyzer.generation;

import static com.example.javaanalyzer.generation.IrFixtures.project;
import static com.example.javaanalyzer.generation.IrFixtures.type;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.ir.CallModel;
import com.example.javaanalyzer.ir.DependencyResolution;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CallGraphTablesTest {

  private static CallModel call(
      String from, String method, int line, String field, String target, String callee) {
    return new CallModel(
        from,
        method,
        List.of("Long"),
        field,
        callee,
        false,
        1,
        type("com.example.Declared"),
        target,
        target == null ? DependencyResolution.AMBIGUOUS : DependencyResolution.PROJECT_TYPE,
        line);
  }

  @Test
  @DisplayName("Lists every call of the project by caller class and line, with unresolved targets")
  void listsCalls() {
    var model =
        project()
            .calls(
                call(
                    "com.example.b.OrderService",
                    "find",
                    22,
                    "repository",
                    "com.example.c.Repo",
                    "findById"),
                call(
                    "com.example.a.OrderController",
                    "get",
                    30,
                    "service",
                    "com.example.b.OrderService",
                    "find"),
                call("com.example.a.OrderController", "get", 12, "notifiers", null, "notify"))
            .build();

    Table table = CallGraphTables.all(model);

    assertThat(table.headers()).containsExactly("呼び出し元", "依存先フィールド", "呼び出し先", "実引数の数");
    assertThat(table.texts())
        .containsExactly(
            List.of("OrderController.get(Long)", "notifiers: Declared", "notify（実装が複数）", "1"),
            List.of("OrderController.get(Long)", "service: Declared", "OrderService.find", "1"),
            List.of("OrderService.find(Long)", "repository: Declared", "Repo.findById", "1"));
    assertThat(table.rows().get(0).get(0).qualifiedName())
        .isEqualTo("com.example.a.OrderController");
  }

  @Test
  @DisplayName("A project without calls gives an empty table")
  void empty() {
    assertThat(CallGraphTables.all(project().build()).isEmpty()).isTrue();
  }
}
