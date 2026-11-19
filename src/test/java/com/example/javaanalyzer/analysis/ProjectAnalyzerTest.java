package com.example.javaanalyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.example.javaanalyzer.analysis.plugin.AnalyzerPlugin;
import com.example.javaanalyzer.ir.AnalysisIssue;
import com.example.javaanalyzer.ir.IssueKind;
import com.example.javaanalyzer.ir.IssueSeverity;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.TypeModel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectAnalyzerTest {

  @TempDir Path projectRoot;

  private final ProjectAnalyzer analyzer = new ProjectAnalyzer(List.of());

  private void write(String relativePath, String content) throws Exception {
    Path file = projectRoot.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private static List<AnalysisIssue> issuesOf(ProjectModel model, IssueKind kind) {
    return model.issues().stream().filter(i -> i.kind() == kind).toList();
  }

  @Test
  @DisplayName("Only src/main/java is analyzed; test sources are excluded")
  void analyzesOnlyMainSources() throws Exception {
    write("src/main/java/com/example/App.java", "package com.example; public class App {}");
    write("src/test/java/com/example/AppTest.java", "package com.example; class AppTest {}");

    ProjectModel model = analyzer.analyze(projectRoot);

    assertThat(model.types())
        .extracting(TypeModel::qualifiedName)
        .containsExactly("com.example.App");
    assertThat(model.issues()).isEmpty();
  }

  @Test
  @DisplayName("No src/main/java yields an empty model without errors")
  void emptyProject() {
    ProjectModel model = analyzer.analyze(projectRoot);

    assertThat(model.types()).isEmpty();
    assertThat(model.rootPackage()).isEmpty();
  }

  @Test
  @DisplayName("A syntax error is reported with file and line; other files are still analyzed")
  void reportsSyntaxErrorAndContinues() throws Exception {
    write("src/main/java/com/example/Good.java", "package com.example; public class Good {}");
    write(
        "src/main/java/com/example/Broken.java",
        "package com.example;\n\npublic class Broken {\n  void x( {\n}\n");

    ProjectModel model = analyzer.analyze(projectRoot);

    assertThat(model.types())
        .extracting(TypeModel::qualifiedName)
        .containsExactly("com.example.Good");
    assertThat(issuesOf(model, IssueKind.SYNTAX_ERROR))
        .singleElement()
        .satisfies(
            issue -> {
              assertThat(issue.severity()).isEqualTo(IssueSeverity.ERROR);
              assertThat(issue.file()).isEqualTo("src/main/java/com/example/Broken.java");
              assertThat(issue.line()).isEqualTo(4);
              assertThat(issue.message()).startsWith("構文解析に失敗しました。");
            });
  }

  @Test
  @DisplayName(
      "Imports under the root package that do not exist are unresolved; others are not judged")
  void reportsUnresolvedImportsUnderRootPackage() throws Exception {
    write(
        "src/main/java/com/example/domain/Order.java",
        "package com.example.domain; public class Order {}");
    write(
        "src/main/java/com/example/service/OrderService.java",
        """
        package com.example.service;

        import com.example.domain.Order;
        import com.example.domain.Missing;
        import com.example.legacy.*;
        import com.example.domain.*;
        import static com.example.domain.Order.create;
        import static com.example.util.Strings.trim;
        import org.springframework.stereotype.Service;
        import some.library.Thing;

        public class OrderService {}
        """);

    ProjectModel model = analyzer.analyze(projectRoot);

    assertThat(model.rootPackage()).isEqualTo("com.example");
    assertThat(issuesOf(model, IssueKind.UNRESOLVED_IMPORT))
        .extracting(AnalysisIssue::file, AnalysisIssue::line, AnalysisIssue::severity)
        .containsExactly(
            tuple("src/main/java/com/example/service/OrderService.java", 4, IssueSeverity.ERROR),
            tuple("src/main/java/com/example/service/OrderService.java", 5, IssueSeverity.ERROR),
            tuple("src/main/java/com/example/service/OrderService.java", 8, IssueSeverity.ERROR));
    assertThat(issuesOf(model, IssueKind.UNRESOLVED_IMPORT).get(0).message())
        .contains("com.example.domain.Missing");
  }

  @Test
  @DisplayName("An import of a class whose file has a syntax error is not reported as unresolved")
  void importOfBrokenFileIsNotUnresolved() throws Exception {
    write(
        "src/main/java/com/example/domain/Broken.java",
        "package com.example.domain; class Broken {");
    write(
        "src/main/java/com/example/service/UsesBroken.java",
        "package com.example.service; import com.example.domain.Broken; class UsesBroken {}");

    ProjectModel model = analyzer.analyze(projectRoot);

    assertThat(issuesOf(model, IssueKind.SYNTAX_ERROR)).hasSize(1);
    assertThat(issuesOf(model, IssueKind.UNRESOLVED_IMPORT)).isEmpty();
  }

  @Test
  @DisplayName("A source that is not valid UTF-8 is read as MS932 with a warning")
  void fallsBackToMs932() throws Exception {
    Path file = projectRoot.resolve("src/main/java/com/example/Greeting.java");
    Files.createDirectories(file.getParent());
    Files.write(
        file,
        "package com.example;\n/** 挨拶 */\npublic class Greeting {}\n"
            .getBytes(Charset.forName("MS932")));

    ProjectModel model = analyzer.analyze(projectRoot, StandardCharsets.UTF_8);

    assertThat(model.types())
        .singleElement()
        .satisfies(t -> assertThat(t.javadoc()).isEqualTo("挨拶"));
    assertThat(issuesOf(model, IssueKind.ENCODING_FALLBACK))
        .singleElement()
        .satisfies(
            issue -> {
              assertThat(issue.severity()).isEqualTo(IssueSeverity.WARNING);
              assertThat(issue.file()).isEqualTo("src/main/java/com/example/Greeting.java");
            });
  }

  @Test
  @DisplayName("Plugins run in order only when they support the project")
  void runsSupportingPluginsOnly() throws Exception {
    write("src/main/java/com/example/App.java", "package com.example; public class App {}");
    List<String> calls = new ArrayList<>();
    AnalyzerPlugin supporting = new RecordingPlugin("yes", true, calls);
    AnalyzerPlugin notSupporting = new RecordingPlugin("no", false, calls);

    new ProjectAnalyzer(List.of(notSupporting, supporting)).analyze(projectRoot);

    assertThat(calls).containsExactly("yes");
  }

  @Test
  @DisplayName("Root package is the longest common package by whole segments")
  void computesCommonPackage() {
    assertThat(ProjectAnalyzer.commonPackage(Set.of("com.example.app", "com.example.application")))
        .isEqualTo("com.example");
    assertThat(ProjectAnalyzer.commonPackage(Set.of("com.example.app")))
        .isEqualTo("com.example.app");
    assertThat(ProjectAnalyzer.commonPackage(Set.of("com.a", "org.b"))).isEmpty();
    assertThat(ProjectAnalyzer.commonPackage(Set.of("", "com.example"))).isEqualTo("com.example");
  }

  @Test
  @DisplayName(
      "A type declared in two files keeps the first by path and records a DUPLICATE_TYPE warning")
  void duplicateTypeIsReportedInsteadOfSilentlyReplaced() throws Exception {
    write(
        "src/main/java/com/example/Foo.java",
        "package com.example; public class Foo { String current; }");
    write(
        "src/main/java/com/example/backup/Foo.java",
        "package com.example; public class Foo { String old; }");

    ProjectModel model = analyzer.analyze(projectRoot);

    assertThat(model.types()).hasSize(1);
    assertThat(model.types().get(0).sourceFile()).isEqualTo("src/main/java/com/example/Foo.java");
    assertThat(model.types().get(0).fields()).extracting(f -> f.name()).containsExactly("current");
    List<AnalysisIssue> duplicates = issuesOf(model, IssueKind.DUPLICATE_TYPE);
    assertThat(duplicates).hasSize(1);
    AnalysisIssue duplicate = duplicates.get(0);
    assertThat(duplicate.severity()).isEqualTo(IssueSeverity.WARNING);
    assertThat(duplicate.file()).isEqualTo("src/main/java/com/example/backup/Foo.java");
    assertThat(duplicate.message())
        .contains("com.example.Foo")
        .contains("src/main/java/com/example/Foo.java");
  }

  @Test
  @DisplayName("A file too deeply nested to parse is a syntax error; the other files are analyzed")
  void tooDeeplyNestedFileIsReportedAndOthersAnalyzed() throws Exception {
    String deep = "\"a\"" + " + \"a\"".repeat(20_000);
    write(
        "src/main/java/com/example/Deep.java",
        "package com.example; class Deep { String s = " + deep + "; }");
    write("src/main/java/com/example/App.java", "package com.example; public class App {}");

    ProjectModel model = new ProjectAnalyzer(List.of()).analyze(projectRoot);

    assertThat(model.types()).extracting(TypeModel::qualifiedName).contains("com.example.App");
    assertThat(issuesOf(model, IssueKind.SYNTAX_ERROR))
        .extracting(AnalysisIssue::file, AnalysisIssue::severity)
        .containsExactly(tuple("src/main/java/com/example/Deep.java", IssueSeverity.ERROR));
  }

  @Test
  @DisplayName("Types with the same simple name in different packages are not duplicates")
  void sameSimpleNameInOtherPackageIsNotDuplicate() throws Exception {
    write("src/main/java/com/example/a/Foo.java", "package com.example.a; public class Foo {}");
    write("src/main/java/com/example/b/Foo.java", "package com.example.b; public class Foo {}");

    ProjectModel model = analyzer.analyze(projectRoot);

    assertThat(model.types()).hasSize(2);
    assertThat(issuesOf(model, IssueKind.DUPLICATE_TYPE)).isEmpty();
  }

  private record RecordingPlugin(String name, boolean supported, List<String> calls)
      implements AnalyzerPlugin {

    @Override
    public boolean supports(AnalysisContext context) {
      return supported;
    }

    @Override
    public void analyze(AnalysisContext context) {
      calls.add(name);
    }
  }
}
