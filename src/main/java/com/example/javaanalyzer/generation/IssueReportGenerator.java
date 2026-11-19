package com.example.javaanalyzer.generation;

import com.example.javaanalyzer.ir.AnalysisIssue;
import com.example.javaanalyzer.ir.IssueKind;
import com.example.javaanalyzer.ir.IssueSeverity;
import com.example.javaanalyzer.ir.ProjectModel;
import java.util.Comparator;
import java.util.List;

/** The analysis issue report (FR-14, SCR-07): errors first, then by kind, file and line. */
public final class IssueReportGenerator {

  public static final String NOTE = "コンパイル失敗を完全には検出しない。";

  private IssueReportGenerator() {}

  /**
   * @param kind only this kind; null for all
   * @param severity only this severity; null for all
   */
  public static Table table(ProjectModel model, IssueKind kind, IssueSeverity severity) {
    return new Table(
        List.of("重要度", "種類", "ファイル", "行", "メッセージ"),
        model.issues().stream()
            .filter(issue -> kind == null || issue.kind() == kind)
            .filter(issue -> severity == null || issue.severity() == severity)
            .sorted(
                Comparator.comparing(AnalysisIssue::severity)
                    .thenComparing(AnalysisIssue::kind)
                    .thenComparing(
                        AnalysisIssue::file, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(
                        AnalysisIssue::line, Comparator.nullsLast(Comparator.naturalOrder())))
            .map(
                issue ->
                    List.of(
                        Cell.of(Labels.severity(issue.severity())),
                        Cell.of(Labels.issueKind(issue.kind())),
                        Cell.of(Labels.orDash(issue.file())),
                        Cell.of(Labels.orDash(issue.line())),
                        Cell.of(issue.message())))
            .toList());
  }
}
