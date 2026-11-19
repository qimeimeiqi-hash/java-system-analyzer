package com.example.javaanalyzer.generation;

import static com.example.javaanalyzer.generation.IrFixtures.project;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.ir.AnalysisIssue;
import com.example.javaanalyzer.ir.IssueKind;
import com.example.javaanalyzer.ir.IssueSeverity;
import com.example.javaanalyzer.ir.ProjectModel;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IssueReportGeneratorTest {

  private static ProjectModel model() {
    return project()
        .issues(
            new AnalysisIssue(
                IssueKind.CIRCULAR_DEPENDENCY,
                IssueSeverity.WARNING,
                null,
                null,
                "循環依存: A → B → A"),
            new AnalysisIssue(
                IssueKind.UNRESOLVED_IMPORT, IssueSeverity.WARNING, "src/B.java", 3, "import x"),
            new AnalysisIssue(IssueKind.SYNTAX_ERROR, IssueSeverity.ERROR, "src/A.java", 7, "構文"),
            new AnalysisIssue(
                IssueKind.VIEW_SETTING_UNRESOLVED,
                IssueSeverity.WARNING,
                "pom.xml",
                null,
                "prefix"))
        .build();
  }

  @Test
  @DisplayName("Lists errors first with Japanese kind names; a missing file or line is '-'")
  void listsIssues() {
    Table table = IssueReportGenerator.table(model(), null, null);

    assertThat(table.headers()).containsExactly("重要度", "種類", "ファイル", "行", "メッセージ");
    assertThat(table.texts())
        .containsExactly(
            List.of("エラー", "構文エラー", "src/A.java", "7", "構文"),
            List.of("警告", "未解決 import", "src/B.java", "3", "import x"),
            List.of("警告", "View 設定", "pom.xml", "-", "prefix"),
            List.of("警告", "循環依存", "-", "-", "循環依存: A → B → A"));
  }

  @Test
  @DisplayName("Can be narrowed by kind and by severity")
  void filters() {
    assertThat(IssueReportGenerator.table(model(), IssueKind.SYNTAX_ERROR, null).texts())
        .hasSize(1);
    assertThat(IssueReportGenerator.table(model(), null, IssueSeverity.WARNING).texts()).hasSize(3);
  }
}
