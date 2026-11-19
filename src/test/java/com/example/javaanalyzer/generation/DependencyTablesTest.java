package com.example.javaanalyzer.generation;

import static com.example.javaanalyzer.generation.IrFixtures.project;
import static com.example.javaanalyzer.generation.IrFixtures.type;
import static com.example.javaanalyzer.generation.IrFixtures.unresolvedType;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.ir.DependencyModel;
import com.example.javaanalyzer.ir.DependencyResolution;
import com.example.javaanalyzer.ir.InjectionKind;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DependencyTablesTest {

  private static final String SERVICE = "com.example.service.OrderService";

  private static final List<DependencyModel> DEPENDENCIES =
      List.of(
          new DependencyModel(
              SERVICE,
              "repository",
              InjectionKind.CONSTRUCTOR,
              type("com.example.repo.OrderRepository"),
              "com.example.repo.OrderRepository",
              DependencyResolution.PROJECT_TYPE),
          new DependencyModel(
              SERVICE,
              "notifier",
              InjectionKind.FIELD,
              type("com.example.service.Notifier"),
              null,
              DependencyResolution.AMBIGUOUS),
          new DependencyModel(
              SERVICE,
              "mailSender",
              InjectionKind.SETTER,
              type("org.springframework.mail.MailSender"),
              null,
              DependencyResolution.EXTERNAL),
          new DependencyModel(
              SERVICE,
              "helper",
              InjectionKind.FIELD,
              unresolvedType("Helper"),
              null,
              DependencyResolution.UNRESOLVED));

  @Test
  @DisplayName("Dependencies that are not drawn are all listed with the reason (screen-design 2.3)")
  void listsUnresolvedDependencies() {
    Table table =
        DependencyTables.unresolved(
            project().dependencies(DEPENDENCIES.toArray(DependencyModel[]::new)).build());

    assertThat(table.headers()).containsExactly("依存元", "メンバー", "宣言型", "理由");
    assertThat(table.texts())
        .containsExactly(
            List.of("OrderService", "helper", "Helper", "型を特定できない"),
            List.of("OrderService", "mailSender", "MailSender", "外部ライブラリ"),
            List.of("OrderService", "notifier", "Notifier", "実装が複数"));
  }

  @Test
  @DisplayName("The full dependency table lists every dependency with how it was resolved")
  void listsAllDependencies() {
    Table table =
        DependencyTables.all(
            project().dependencies(DEPENDENCIES.toArray(DependencyModel[]::new)).build());

    assertThat(table.headers()).containsExactly("依存元", "メンバー", "注入方式", "宣言型", "解決先", "解決方法");
    assertThat(table.texts())
        .containsExactly(
            List.of("OrderService", "helper", "フィールド", "Helper", "-", "型を特定できない"),
            List.of("OrderService", "mailSender", "setter", "MailSender", "-", "外部ライブラリ"),
            List.of("OrderService", "notifier", "フィールド", "Notifier", "-", "実装が複数"),
            List.of(
                "OrderService",
                "repository",
                "コンストラクタ",
                "OrderRepository",
                "OrderRepository",
                "直接解決"));
  }
}
