package com.example.javaanalyzer.generation.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.analysis.ProjectAnalyzer;
import com.example.javaanalyzer.analysis.plugin.mybatis.MyBatisPlugin;
import com.example.javaanalyzer.analysis.plugin.springboot.SpringBootPlugin;
import com.example.javaanalyzer.analysis.plugin.view.ViewTemplatePlugin;
import com.example.javaanalyzer.generation.Diagram;
import com.example.javaanalyzer.generation.ProjectFacts;
import com.example.javaanalyzer.generation.export.Block.DiagramBlock;
import com.example.javaanalyzer.generation.export.Block.Heading;
import com.example.javaanalyzer.ir.ComponentModel;
import com.example.javaanalyzer.ir.ComponentRole;
import com.example.javaanalyzer.ir.EntityKind;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.io.IrReader;
import com.example.javaanalyzer.ir.io.IrWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentsAssemblerTest {

  private static final ProjectFacts FACTS = new ProjectFacts("3.3.4", "pom.xml", "UTF-8");

  @TempDir Path irDir;

  private ProjectModel savedIr(String sample) {
    ProjectModel analyzed =
        new ProjectAnalyzer(
                List.of(new SpringBootPlugin(), new MyBatisPlugin(), new ViewTemplatePlugin()))
            .analyze(Path.of("src/test/resources/sample-projects", sample));
    IrWriter.write(analyzed, irDir);
    return IrReader.read(irDir);
  }

  private static List<String> chapters(ExportDocument document) {
    return document.blocks().stream()
        .filter(b -> b instanceof Heading h && h.level() == 1)
        .map(b -> ((Heading) b).text())
        .toList();
  }

  @Test
  @DisplayName("The document set has one chapter per screen, in the navigation order")
  void chaptersFollowTheScreens() {
    ExportDocument document = DocumentsAssembler.documents(savedIr("lombok-jpa-demo"), FACTS);

    assertThat(document.title()).isEqualTo("成果物一式（com.example.shop）");
    assertThat(chapters(document))
        .containsExactly(
            "解析総覧",
            "アーキテクチャ・依存関係",
            "API一覧",
            "ER図",
            "クラス別概要",
            "解析エラーレポート",
            "MyBatis",
            "Configuration",
            "画面一覧");
  }

  @Test
  @DisplayName("Every diagram of the screens is included, each with a fallback table")
  void includesDiagrams() {
    ExportDocument document = DocumentsAssembler.documents(savedIr("lombok-jpa-demo"), FACTS);

    assertThat(document.diagrams())
        .extracting(Diagram::id)
        .containsExactly("layers", "packages", "components", "er");
    assertThat(document.blocks())
        .filteredOn(DiagramBlock.class::isInstance)
        .allMatch(block -> ((DiagramBlock) block).fallback() != null);
  }

  @Test
  @DisplayName("Each class gets its FR-09 overview, and the inspection facts are in the overview")
  void includesClassesAndFacts() {
    String markdown =
        MarkdownRenderer.render(DocumentsAssembler.documents(savedIr("lombok-jpa-demo"), FACTS));

    assertThat(markdown)
        .contains("### OrderService（Service）")
        .contains("OrderService は com.example.shop.service パッケージの Service です。")
        .contains("| Spring Boot | 3.3.4 |")
        .contains("```mermaid\nerDiagram");
  }

  @Test
  @DisplayName("The call graph notes appear once at the top of the class chapter, not per class")
  void callNotesOnce() {
    String markdown =
        MarkdownRenderer.render(DocumentsAssembler.documents(savedIr("lombok-jpa-demo"), FACTS));

    String note = "> 呼び出し関係（簡易）：既知の DI 依存先フィールドに対する呼び出しのみをヒューリスティックに検出したもので、オーバーロードと多態を解決しない。";
    assertThat(markdown.split(java.util.regex.Pattern.quote(note), -1)).hasSize(2);
    int chapter = markdown.indexOf("## クラス別概要");
    int firstClass = markdown.indexOf("### ", chapter);
    assertThat(markdown.indexOf(note)).isBetween(chapter, firstClass);
    assertThat(markdown).contains("#### 呼び出し関係（簡易）");
  }

  /** The Markdown of one class overview: from its heading to the next class heading. */
  private static String classPart(String markdown, String heading) {
    int start = markdown.indexOf("### " + heading + "\n", markdown.indexOf("## クラス別概要"));
    assertThat(start).as(heading).isPositive();
    int end = markdown.indexOf("\n### ", start + 1);
    int chapterEnd = markdown.indexOf("\n## ", start + 1);
    return markdown.substring(start, Math.min(end < 0 ? markdown.length() : end, chapterEnd));
  }

  @Test
  @DisplayName("Empty field, constructor and method sections are left out; other empties stay")
  void emptyMemberSectionsOmitted() {
    String markdown =
        MarkdownRenderer.render(DocumentsAssembler.documents(savedIr("lombok-jpa-demo"), FACTS));

    String repository = classPart(markdown, "CustomerRepository（Repository）");
    assertThat(repository)
        .doesNotContain("#### フィールド")
        .doesNotContain("#### コンストラクタ")
        .doesNotContain("#### 宣言メソッド")
        .contains("#### 被依存");
    String unused = classPart(markdown, "LegacyReportService（Service）");
    assertThat(unused)
        .contains("#### 公開メソッド\n\n検出されませんでした")
        .contains("#### 依存先\n\n検出されませんでした")
        .contains("#### 被依存\n\n検出されませんでした")
        .contains("#### フィールド\n\n| 名前 |")
        .doesNotContain("#### コンストラクタ")
        .doesNotContain("#### その他のメソッド");
  }

  @Test
  @DisplayName(
      "Entity overviews refer to the ER chapter instead of repeating columns and relations")
  void entityOverviewRefersToErChapter() {
    String markdown =
        MarkdownRenderer.render(DocumentsAssembler.documents(savedIr("lombok-jpa-demo"), FACTS));

    String customer = classPart(markdown, "Customer（Entity）");
    assertThat(customer)
        .contains("カラムと関連は「ER図」の章の Customer（エンティティ）を参照。")
        .contains("#### テーブル")
        .contains("#### 対応するリポジトリ")
        .doesNotContain("#### カラム")
        .doesNotContain("#### 関連");
    assertThat(classPart(markdown, "Address（Embeddable）"))
        .contains("カラムと関連は「ER図」の章の Address（Embeddable）を参照。");
    assertThat(markdown).contains("### Customer（エンティティ）\n\nカラム\n\n| フィールド |");
  }

  @Test
  @DisplayName("The screen tables show relative paths after a note naming the template directory")
  void templateDirectoryNote() {
    String markdown =
        MarkdownRenderer.render(DocumentsAssembler.documents(savedIr("thymeleaf-demo"), FACTS));

    String note = "> テンプレートの場所：src/main/resources/templates/";
    int screens = markdown.indexOf("### 画面\n");
    assertThat(markdown.indexOf(note)).isGreaterThan(screens);
    assertThat(markdown.indexOf(note)).isLessThan(markdown.indexOf("| テンプレート |"));
    assertThat(markdown).doesNotContain("| src/main/resources/templates/");
  }

  @Test
  @DisplayName("Above 50 entities, the ER diagram is split per package in both documents")
  void splitsLargeErDiagram() {
    ProjectModel base = savedIr("lombok-jpa-demo");
    List<EntityModel> entities = new ArrayList<>(base.entities());
    for (int i = 0; i < 60; i++) {
      entities.add(
          new EntityModel(
              "com.example.shop.archive.Archived" + i,
              EntityKind.ENTITY,
              null,
              null,
              null,
              null,
              List.of(),
              List.of()));
    }
    ProjectModel large = withEntities(base, entities);

    List<String> ids =
        DocumentsAssembler.documents(large, FACTS).diagrams().stream().map(Diagram::id).toList();
    assertThat(ids).contains("er-1", "er-2").doesNotContain("er");
    assertThat(
            BasicDesignAssembler.document(large, FACTS, java.time.LocalDate.of(2026, 11, 11))
                .diagrams()
                .stream()
                .map(Diagram::id)
                .toList())
        .contains("er-1", "er-2")
        .doesNotContain("er");
    assertThat(DocumentsAssembler.documents(base, FACTS).diagrams())
        .extracting(Diagram::id)
        .contains("er");
  }

  private static ProjectModel withEntities(ProjectModel base, List<EntityModel> entities) {
    return new ProjectModel(
        base.rootPackage(),
        base.types(),
        base.components(),
        base.endpoints(),
        entities,
        base.repositories(),
        base.beanDefinitions(),
        base.configurationProperties(),
        base.mappers(),
        base.views(),
        base.dependencies(),
        base.calls(),
        base.packages(),
        base.issues());
  }

  @Test
  @DisplayName("Above the node limit, the component diagram is split per package for the export")
  void splitsLargeComponentDiagram() {
    ProjectModel base = savedIr("lombok-jpa-demo");
    List<ComponentModel> components = new ArrayList<>(base.components());
    for (int i = 0; i < 60; i++) {
      components.add(
          new ComponentModel("com.example.shop.extra.C" + i, ComponentRole.COMPONENT, List.of()));
    }
    ProjectModel large =
        new ProjectModel(
            base.rootPackage(),
            base.types(),
            components,
            base.endpoints(),
            base.entities(),
            base.repositories(),
            base.beanDefinitions(),
            base.configurationProperties(),
            base.mappers(),
            base.views(),
            base.dependencies(),
            base.calls(),
            base.packages(),
            base.issues());

    assertThat(DocumentsAssembler.documents(large, FACTS).diagrams())
        .extracting(Diagram::id)
        .contains("components-1", "components-2")
        .doesNotContain("components");
  }
}
