package com.example.javaanalyzer.generation.export;

import com.example.javaanalyzer.generation.ApiListGenerator;
import com.example.javaanalyzer.generation.Cell;
import com.example.javaanalyzer.generation.ClassListGenerator;
import com.example.javaanalyzer.generation.ClassOverview;
import com.example.javaanalyzer.generation.ClassOverviewGenerator;
import com.example.javaanalyzer.generation.ClassTemplate;
import com.example.javaanalyzer.generation.ConfigurationReportGenerator;
import com.example.javaanalyzer.generation.DependencyTables;
import com.example.javaanalyzer.generation.Diagram;
import com.example.javaanalyzer.generation.EntityTables;
import com.example.javaanalyzer.generation.IssueReportGenerator;
import com.example.javaanalyzer.generation.KeyValue;
import com.example.javaanalyzer.generation.Labels;
import com.example.javaanalyzer.generation.MapperReport;
import com.example.javaanalyzer.generation.MyBatisReportGenerator;
import com.example.javaanalyzer.generation.ProjectFacts;
import com.example.javaanalyzer.generation.ProjectStatistics;
import com.example.javaanalyzer.generation.Section;
import com.example.javaanalyzer.generation.StatementReport;
import com.example.javaanalyzer.generation.Table;
import com.example.javaanalyzer.generation.ViewReportGenerator;
import com.example.javaanalyzer.generation.diagram.ComponentDiagram;
import com.example.javaanalyzer.generation.diagram.DependencyDiagramGenerator;
import com.example.javaanalyzer.generation.diagram.ErDiagramGenerator;
import com.example.javaanalyzer.generation.diagram.ViewTransitionDiagramGenerator;
import com.example.javaanalyzer.generation.export.Block.Code;
import com.example.javaanalyzer.generation.export.Block.DiagramBlock;
import com.example.javaanalyzer.generation.export.Block.Heading;
import com.example.javaanalyzer.generation.export.Block.KeyValueBlock;
import com.example.javaanalyzer.generation.export.Block.Note;
import com.example.javaanalyzer.generation.export.Block.Paragraph;
import com.example.javaanalyzer.generation.export.Block.TableBlock;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.TypeModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Puts every result screen into one document, the "documents" export of SCR-11 (FR-11). The content
 * is exactly what the screens show, built by the same generators.
 */
public final class DocumentsAssembler {

  private DocumentsAssembler() {}

  public static ExportDocument documents(ProjectModel model, ProjectFacts facts) {
    List<Block> blocks = new ArrayList<>();
    overview(blocks, model, facts);
    architecture(blocks, model);
    blocks.add(new Heading(1, "API一覧"));
    blocks.add(new TableBlock(ApiListGenerator.table(model, null)));
    er(blocks, model);
    classes(blocks, model);
    blocks.add(new Heading(1, "解析エラーレポート"));
    blocks.add(new TableBlock(IssueReportGenerator.table(model, null, null)));
    blocks.add(new Note(IssueReportGenerator.NOTE));
    mybatis(blocks, model, 1);
    configuration(blocks, model, 1);
    views(blocks, model, 1);
    return new ExportDocument("成果物一式（" + rootName(model) + "）", blocks);
  }

  static String rootName(ProjectModel model) {
    return model.rootPackage() == null || model.rootPackage().isEmpty()
        ? "ルートパッケージなし"
        : model.rootPackage();
  }

  /** Inspection facts as key-values. */
  static List<KeyValue> factValues(ProjectModel model, ProjectFacts facts) {
    return List.of(
        KeyValue.of("ルートパッケージ", rootName(model)),
        KeyValue.of(
            "Spring Boot", facts.springBootVersion() == null ? "不明" : facts.springBootVersion()),
        KeyValue.of("pom.xml", Labels.orDash(facts.pomPath())),
        KeyValue.of("文字コード", facts.sourceEncoding()));
  }

  static Table statisticsTable(ProjectStatistics statistics) {
    List<List<Cell>> rows = new ArrayList<>();
    for (ProjectStatistics.Item item : statistics.items()) {
      rows.add(List.of(Cell.of(item.label()), Cell.of(String.valueOf(item.count()))));
    }
    rows.add(
        List.of(
            Cell.of("解析エラー（エラー / 警告）"),
            Cell.of(statistics.errorCount() + " / " + statistics.warningCount())));
    return new Table(List.of("項目", "件数"), rows);
  }

  /**
   * The ER diagrams for documents: one diagram, or one per package when there are more entities
   * than {@link DependencyDiagramGenerator#MAX_UNFILTERED_NODES} (screen-design SCR-11 "大きな図").
   */
  static List<Diagram> erDiagrams(ProjectModel model) {
    if (ErDiagramGenerator.entityCount(model.entities())
        > DependencyDiagramGenerator.MAX_UNFILTERED_NODES) {
      return ErDiagramGenerator.diagramsPerPackage(model.entities(), model.rootPackage());
    }
    return ErDiagramGenerator.diagram(model.entities()).stream().toList();
  }

  /**
   * The dependency diagrams for documents: layers, packages, then components, split per package
   * when the project is above the node limit (screen-design SCR-11 "大きな図").
   */
  static List<Diagram> dependencyDiagrams(ProjectModel model) {
    List<Diagram> diagrams = new ArrayList<>();
    DependencyDiagramGenerator.layers(model).ifPresent(diagrams::add);
    DependencyDiagramGenerator.packages(model).ifPresent(diagrams::add);
    ComponentDiagram components = DependencyDiagramGenerator.components(model, null);
    if (components.filterRequired()) {
      diagrams.addAll(DependencyDiagramGenerator.componentsPerPackage(model));
    } else if (components.diagram() != null) {
      diagrams.add(components.diagram());
    }
    return diagrams;
  }

  static void addSection(List<Block> blocks, int level, Section section) {
    blocks.add(new Heading(level, section.title()));
    if (!section.keyValues().isEmpty()) {
      blocks.add(new KeyValueBlock(section.keyValues()));
    }
    if (section.table() != null) {
      blocks.add(new TableBlock(section.table()));
    }
    section.notes().forEach(note -> blocks.add(new Note(note)));
  }

  /**
   * One class overview, with the sections of {@link #documentSections}. An entity refers to its
   * columns and relations in the ER chapter instead of repeating them.
   */
  static void classOverview(
      List<Block> blocks, int level, ClassOverview overview, ProjectModel model) {
    blocks.add(new Heading(level, overview.heading()));
    blocks.add(new Paragraph(overview.summary()));
    if (overview.template() == ClassTemplate.ENTITY) {
      model.entities().stream()
          .filter(entity -> entity.typeName().equals(overview.typeName()))
          .findFirst()
          .ifPresent(
              entity ->
                  blocks.add(new Paragraph("カラムと関連は「ER図」の章の " + entityHeading(entity) + "を参照。")));
    }
    documentSections(overview).forEach(section -> addSection(blocks, level + 1, section));
  }

  /**
   * The sections of a class overview as the documents show them: without empty member sections,
   * without the entity tables that the data model chapter already has, and without the call graph
   * notes that are stated once per document. The class page keeps all of them.
   */
  static List<Section> documentSections(ClassOverview overview) {
    return overview.sections().stream()
        .filter(
            section ->
                !(ClassOverviewGenerator.MEMBER_SECTIONS.contains(section.title())
                    && section.table() != null
                    && section.table().isEmpty()))
        .filter(
            section ->
                !(overview.template() == ClassTemplate.ENTITY
                    && ClassOverviewGenerator.ENTITY_TABLE_SECTIONS.contains(section.title())))
        .map(DocumentsAssembler::withoutCallNotes)
        .toList();
  }

  /** The heading of an entity in the ER chapter, e.g. "Customer（エンティティ）". */
  static String entityHeading(EntityModel entity) {
    return Labels.simpleName(entity.typeName()) + "（" + Labels.entityKind(entity.kind()) + "）";
  }

  /**
   * The section without the notes repeated in every class ({@link
   * ClassOverviewGenerator#CALL_NOTES}).
   */
  static Section withoutCallNotes(Section section) {
    return new Section(
        section.title(),
        section.keyValues(),
        section.table(),
        section.emptyMessage(),
        section.notes().stream()
            .filter(note -> !ClassOverviewGenerator.CALL_NOTES.contains(note))
            .toList());
  }

  static void entityTables(List<Block> blocks, ProjectModel model, int level) {
    blocks.add(new Heading(level, "エンティティ一覧"));
    blocks.add(new TableBlock(EntityTables.entityList(model.entities())));
    for (EntityModel entity :
        model.entities().stream().sorted(Comparator.comparing(EntityModel::typeName)).toList()) {
      blocks.add(new Heading(level, entityHeading(entity)));
      blocks.add(new Paragraph("カラム"));
      blocks.add(new TableBlock(EntityTables.columns(entity)));
      blocks.add(new Paragraph("関連"));
      blocks.add(new TableBlock(EntityTables.relations(entity)));
    }
  }

  static void mybatis(List<Block> blocks, ProjectModel model, int level) {
    blocks.add(new Heading(level, "MyBatis"));
    blocks.add(new Note(MyBatisReportGenerator.INCLUDE_NOTE));
    List<MapperReport> mappers = new ArrayList<>(MyBatisReportGenerator.mappers(model));
    List<MapperReport> orphans = MyBatisReportGenerator.namespacesWithoutInterface(model);
    if (mappers.isEmpty() && orphans.isEmpty()) {
      blocks.add(new Paragraph(Section.NOT_DETECTED));
      return;
    }
    mappers.addAll(orphans);
    for (MapperReport mapper : mappers) {
      blocks.add(
          new Heading(
              level + 1,
              mapper.interfaceType() == null
                  ? mapper.title() + "（インタフェースのない namespace）"
                  : mapper.title()));
      blocks.add(new KeyValueBlock(mapper.information()));
      mapper.notes().forEach(note -> blocks.add(new Note(note)));
      if (mapper.methods() != null) {
        blocks.add(new TableBlock(mapper.methods()));
      }
      for (StatementReport statement : mapper.statements()) {
        blocks.add(new Paragraph("文：" + statement.heading()));
        statement.notes().forEach(note -> blocks.add(new Note(note)));
        blocks.add(new Code(statement.sql()));
      }
    }
  }

  static void configuration(List<Block> blocks, ProjectModel model, int level) {
    blocks.add(new Heading(level, "Configuration"));
    blocks.add(new Heading(level + 1, "@Bean 定義"));
    blocks.add(new TableBlock(ConfigurationReportGenerator.beans(model)));
    blocks.add(new Note("Bean 名は明記されたもののみ表示し、既定名は推測しない。Bean の内容は解釈しない。"));
    blocks.add(new Heading(level + 1, "@ConfigurationProperties"));
    blocks.add(new TableBlock(ConfigurationReportGenerator.properties(model)));
    blocks.add(new Heading(level + 1, "設定クラス（@Configuration）"));
    blocks.add(new TableBlock(ConfigurationReportGenerator.configurationClasses(model)));
  }

  static void views(List<Block> blocks, ProjectModel model, int level) {
    blocks.add(new Heading(level, "画面一覧"));
    blocks.add(new Heading(level + 1, "画面"));
    ViewReportGenerator.templateDirectoryNote(model).ifPresent(note -> blocks.add(new Note(note)));
    blocks.add(new TableBlock(ViewReportGenerator.screens(model)));
    Table transitions = ViewReportGenerator.transitions(model);
    Optional<Diagram> diagram = ViewTransitionDiagramGenerator.diagram(model);
    if (diagram.isPresent()) {
      blocks.add(new Heading(level + 1, "画面遷移図"));
      blocks.add(new DiagramBlock(diagram.get(), transitions));
      blocks.add(new Note("実線＝遷移先メソッドが返す画面 / 点線＝redirect・forward を 1 段たどった画面。部品（include）は含めない。"));
    }
    blocks.add(new Heading(level + 1, "画面ごとの遷移先"));
    blocks.add(new TableBlock(transitions));
    blocks.add(new Heading(level + 1, "Controller と対応付けできなかった View 名"));
    blocks.add(new TableBlock(ViewReportGenerator.unmatched(model)));
  }

  private static void overview(List<Block> blocks, ProjectModel model, ProjectFacts facts) {
    blocks.add(new Heading(1, "解析総覧"));
    blocks.add(new KeyValueBlock(factValues(model, facts)));
    blocks.add(new Heading(2, "プロジェクト統計"));
    blocks.add(new TableBlock(statisticsTable(ProjectStatistics.of(model))));
  }

  private static void architecture(List<Block> blocks, ProjectModel model) {
    blocks.add(new Heading(1, "アーキテクチャ・依存関係"));
    Table allDependencies = DependencyTables.all(model);
    for (Diagram diagram : dependencyDiagrams(model)) {
      blocks.add(new Heading(2, diagram.title()));
      blocks.add(new DiagramBlock(diagram, allDependencies));
    }
    blocks.add(new Note("実線＝直接解決 / 点線＝単一実装から解決（@Bean や @Profile による別実装の可能性がある） / 赤枠＝循環依存"));
    blocks.add(new Heading(2, "依存関係一覧"));
    blocks.add(new TableBlock(allDependencies));
    blocks.add(new Heading(2, "解決できなかった依存"));
    blocks.add(new TableBlock(DependencyTables.unresolved(model)));
  }

  private static void er(List<Block> blocks, ProjectModel model) {
    blocks.add(new Heading(1, "ER図"));
    Table entityList = EntityTables.entityList(model.entities());
    for (Diagram diagram : erDiagrams(model)) {
      if (!diagram.id().equals(ErDiagramGenerator.DIAGRAM_ID)) {
        blocks.add(new Heading(2, diagram.title()));
      }
      blocks.add(new DiagramBlock(diagram, entityList));
    }
    blocks.add(new Note("図はエンティティ（@Entity）のみ。親クラスのカラムは子エンティティに展開しない。テーブル名・カラム名の既定名は推測しない。"));
    entityTables(blocks, model, 2);
  }

  private static void classes(List<Block> blocks, ProjectModel model) {
    blocks.add(new Heading(1, "クラス別概要"));
    ClassOverviewGenerator.CALL_NOTES.forEach(note -> blocks.add(new Note("呼び出し関係（簡易）：" + note)));
    blocks.add(new TableBlock(ClassListGenerator.table(model, null, null)));
    for (TypeModel type :
        model.types().stream()
            .sorted(
                Comparator.comparing(TypeModel::packageName).thenComparing(TypeModel::simpleName))
            .toList()) {
      ClassOverviewGenerator.of(model, type.qualifiedName())
          .ifPresent(overview -> classOverview(blocks, 2, overview, model));
    }
  }
}
