package com.example.javaanalyzer.generation.export;

import com.example.javaanalyzer.generation.ApiListGenerator;
import com.example.javaanalyzer.generation.CallGraphTables;
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
import com.example.javaanalyzer.generation.diagram.ViewTransitionDiagramGenerator;
import com.example.javaanalyzer.generation.export.Block.BulletList;
import com.example.javaanalyzer.generation.export.Block.Caption;
import com.example.javaanalyzer.generation.export.Block.Code;
import com.example.javaanalyzer.generation.export.Block.Cover;
import com.example.javaanalyzer.generation.export.Block.DiagramBlock;
import com.example.javaanalyzer.generation.export.Block.Heading;
import com.example.javaanalyzer.generation.export.Block.KeyValueBlock;
import com.example.javaanalyzer.generation.export.Block.Note;
import com.example.javaanalyzer.generation.export.Block.PageBreak;
import com.example.javaanalyzer.generation.export.Block.Paragraph;
import com.example.javaanalyzer.generation.export.Block.TableBlock;
import com.example.javaanalyzer.generation.export.Block.TableOfContents;
import com.example.javaanalyzer.ir.EntityKind;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.TypeModel;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The basic design document (FR-19): the results of FR-05 to FR-18 reorganized into the confirmed
 * eight chapters (screen-design SCR-11 "基本設計書の構成"), with a cover, revision history, table of
 * contents, numbered sections and numbered figures and tables. No AI is used (NFR-02).
 *
 * <p>What the source cannot tell, such as the business purpose or the author, is left as a manual
 * entry instead of being guessed (NFR-06). It is a basic design only: no processing flows or
 * internal logic, which belong to the detailed design (V2/V4).
 */
public final class BasicDesignAssembler {

  public static final String MANUAL_ENTRY = "（手動記入欄）";

  static final String TITLE = "基本設計書";

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");

  /** Fixed statement of the intended readers and uses, placed at the start of chapter 1. */
  static final String PURPOSE =
      "本書は、本システムに新たに参画する開発者が既存システムの構造を短期間で把握するため、"
          + "および既存コードを変更する開発者が変更前に関連範囲を確認するために、"
          + "ソースコードから自動抽出した事実情報に基づいて生成された設計書である。"
          + "業務ロジックの解釈や処理フローの説明は含まない（詳細設計書の範囲外）。"
          + "後者の用途で第6章の「6.3 簡易呼び出し関係」を参照する場合は、"
          + "オーバーロード・多態・外部ライブラリ呼び出しを解決しないヒューリスティックな検出である旨に留意し、"
          + "網羅的な影響範囲調査の代替とはしないこと。";

  /** Fixed statements on what this document does not cover, from the tool's specification. */
  static final List<String> LIMITS =
      List.of(
          "ソースコードを実行せず、静的解析のみで作成している。コンパイルが通るかどうかは検証せず、構文エラーと未解決 import のみを検出する。",
          "実行時にのみ確定する設定（プロファイル、環境変数、プレースホルダ等）は解釈しない。",
          "テーブル名・カラム名・Bean 名は明記されたもののみ記載し、命名規則による既定名を推測しない。",
          "依存関係の「単一実装から解決」は、@Bean や @Profile による別実装が存在する可能性がある。",
          "呼び出し関係は既知の DI 依存先フィールドに対する呼び出しのみをヒューリスティックに検出したもので、オーバーロードと多態を解決しない。外部ライブラリの呼び出し、コンストラクタ内の呼び出しは含まない。",
          "MyBatis の SQL は記述どおりに記載し、<include> の展開や SQL の意味解析は行わない。基底 Mapper の自動生成メソッドは記載しない。",
          "画面テンプレートの式は評価しない。式で書かれたリンクや View 名は未解決として記載する。",
          "処理フロー・内部ロジックの説明は本書の対象外（詳細設計書の範囲）。");

  private BasicDesignAssembler() {}

  public static ExportDocument document(ProjectModel model, ProjectFacts facts, LocalDate created) {
    Builder builder = new Builder();
    String date = created.format(DATE);
    builder.add(
        new Cover(
            TITLE,
            List.of(
                KeyValue.of("システム名", MANUAL_ENTRY),
                KeyValue.of("対象", DocumentsAssembler.rootName(model)),
                KeyValue.of("作成日", date)),
            "本書は、Javaシステム解析支援ツール V1 がソースコードの静的解析結果から自動生成した。"
                + "コードから抽出できない事項は「"
                + MANUAL_ENTRY
                + "」として残している。"));
    builder.add(new PageBreak());
    builder.add(new Heading(1, "改訂履歴"));
    builder.add(
        new TableBlock(
            new Table(
                List.of("版", "日付", "内容", "作成者", "承認者"),
                List.of(
                    List.of(
                        Cell.of("1.0"),
                        Cell.of(date),
                        Cell.of("初版（本ツールによる自動生成）"),
                        Cell.of(MANUAL_ENTRY),
                        Cell.of(MANUAL_ENTRY))))));
    builder.add(new TableOfContents());
    builder.add(new PageBreak());

    overview(builder, model, facts);
    architecture(builder, model);
    screens(builder, model);
    api(builder, model);
    data(builder, model);
    components(builder, model);
    configuration(builder, model);
    limits(builder, model);
    return new ExportDocument(TITLE, builder.blocks);
  }

  private static void overview(Builder builder, ProjectModel model, ProjectFacts facts) {
    builder.chapter("システム概要");
    builder.add(new Paragraph(PURPOSE));
    builder.section("目的・業務概要");
    builder.add(new Paragraph(MANUAL_ENTRY));
    builder.section("技術構成");
    List<KeyValue> technology = new ArrayList<>(DocumentsAssembler.factValues(model, facts));
    technology.add(KeyValue.of("ビルドツール", "Maven"));
    builder.add(new KeyValueBlock(technology));
    builder.section("プロジェクト統計");
    builder.table("プロジェクト統計", DocumentsAssembler.statisticsTable(ProjectStatistics.of(model)));
  }

  private static void architecture(Builder builder, ProjectModel model) {
    builder.chapter("アーキテクチャ");
    Table allDependencies = DependencyTables.all(model);
    List<Diagram> diagrams = DocumentsAssembler.dependencyDiagrams(model);
    builder.section("レイヤー構成");
    builder.figures(
        diagrams.stream().filter(d -> d.id().equals("layers")).toList(), allDependencies);
    builder.section("パッケージ依存");
    builder.figures(
        diagrams.stream().filter(d -> d.id().equals("packages")).toList(), allDependencies);
    builder.section("コンポーネント依存");
    builder.figures(
        diagrams.stream().filter(d -> d.id().startsWith("components")).toList(), allDependencies);
    builder.add(new Note("実線＝直接解決 / 点線＝単一実装から解決 / 赤枠＝循環依存"));
    builder.section("依存関係一覧");
    builder.table("依存関係一覧", allDependencies);
    builder.section("解決できなかった依存");
    builder.table("解決できなかった依存", DependencyTables.unresolved(model));
  }

  private static void screens(Builder builder, ProjectModel model) {
    builder.chapter("画面設計");
    Table transitions = ViewReportGenerator.transitions(model);
    builder.section("画面一覧");
    ViewReportGenerator.templateDirectoryNote(model).ifPresent(note -> builder.add(new Note(note)));
    builder.table("画面一覧", ViewReportGenerator.screens(model));
    builder.section("画面遷移図");
    Optional<Diagram> diagram = ViewTransitionDiagramGenerator.diagram(model);
    builder.figures(diagram.stream().toList(), transitions);
    diagram.ifPresent(
        unused ->
            builder.add(
                new Note("実線＝遷移先メソッドが返す画面 / 点線＝redirect・forward を 1 段たどった画面。部品（include）は含めない。")));
    builder.section("画面ごとの遷移先");
    builder.table("画面ごとの遷移先", transitions);
    builder.section("Controller と対応付けできなかった View 名");
    builder.table("対応付けできなかった View 名", ViewReportGenerator.unmatched(model));
  }

  private static void api(Builder builder, ProjectModel model) {
    builder.chapter("API 設計");
    builder.section("API 一覧");
    builder.table("API 一覧", ApiListGenerator.table(model, null));
  }

  private static void data(Builder builder, ProjectModel model) {
    builder.chapter("データ設計");
    List<EntityModel> entities =
        model.entities().stream().sorted(Comparator.comparing(EntityModel::typeName)).toList();
    builder.section("ER 図");
    builder.figures(
        DocumentsAssembler.erDiagrams(model), EntityTables.entityList(model.entities()));
    builder.section("エンティティ定義");
    builder.table("エンティティ定義", EntityTables.entityList(model.entities()));
    builder.section("カラム定義");
    if (entities.isEmpty()) {
      builder.add(new Paragraph(Section.NOT_DETECTED));
    }
    for (EntityModel entity : entities) {
      builder.subsection(entityHeading(entity));
      builder.columnSections.put(entity.typeName(), builder.subsectionNumber());
      builder.table(Labels.simpleName(entity.typeName()) + " のカラム", EntityTables.columns(entity));
    }
    builder.section("関連定義");
    if (entities.isEmpty()) {
      builder.add(new Paragraph(Section.NOT_DETECTED));
    }
    for (EntityModel entity : entities) {
      builder.subsection(entityHeading(entity));
      builder.relationSections.put(entity.typeName(), builder.subsectionNumber());
      builder.table(Labels.simpleName(entity.typeName()) + " の関連", EntityTables.relations(entity));
    }
    builder.section("MyBatis の SQL 一覧");
    builder.add(new Note(MyBatisReportGenerator.INCLUDE_NOTE));
    List<MapperReport> mappers = new ArrayList<>(MyBatisReportGenerator.mappers(model));
    mappers.addAll(MyBatisReportGenerator.namespacesWithoutInterface(model));
    if (mappers.isEmpty()) {
      builder.add(new Paragraph(Section.NOT_DETECTED));
    }
    for (MapperReport mapper : mappers) {
      builder.subsection(
          mapper.interfaceType() == null
              ? mapper.title() + "（インタフェースのない namespace）"
              : mapper.title());
      builder.add(new KeyValueBlock(mapper.information()));
      mapper.notes().forEach(note -> builder.add(new Note(note)));
      if (mapper.methods() != null) {
        builder.table(mapper.title() + " のメソッド", mapper.methods());
      }
      for (StatementReport statement : mapper.statements()) {
        builder.add(new Paragraph("文：" + statement.heading()));
        statement.notes().forEach(note -> builder.add(new Note(note)));
        builder.add(new Code(statement.sql()));
      }
    }
  }

  private static void components(Builder builder, ProjectModel model) {
    builder.chapter("コンポーネント設計");
    builder.section("クラス一覧");
    builder.table("クラス一覧", ClassListGenerator.table(model, null, null));
    builder.section("クラス別概要");
    List<TypeModel> types =
        model.types().stream()
            .sorted(
                Comparator.comparing(TypeModel::packageName).thenComparing(TypeModel::simpleName))
            .toList();
    if (types.isEmpty()) {
      builder.add(new Paragraph(Section.NOT_DETECTED));
    }
    for (TypeModel type : types) {
      Optional<ClassOverview> overview = ClassOverviewGenerator.of(model, type.qualifiedName());
      if (overview.isEmpty()) {
        continue;
      }
      builder.subsection(overview.get().heading());
      builder.add(new Paragraph(overview.get().summary()));
      String columns = builder.columnSections.get(type.qualifiedName());
      if (overview.get().template() == ClassTemplate.ENTITY && columns != null) {
        builder.add(
            new Paragraph(
                "カラムは "
                    + columns
                    + "、関連は "
                    + builder.relationSections.get(type.qualifiedName())
                    + " を参照。"));
      }
      // The call graph notes are stated once in 6.3 instead of in every class
      for (Section section : DocumentsAssembler.documentSections(overview.get())) {
        builder.add(new Heading(4, section.title()));
        if (!section.keyValues().isEmpty()) {
          builder.add(new KeyValueBlock(section.keyValues()));
        }
        if (section.table() != null) {
          builder.add(new TableBlock(section.table()));
        }
        section.notes().forEach(note -> builder.add(new Note(note)));
      }
    }
    builder.section("簡易呼び出し関係");
    builder.table("簡易呼び出し関係", CallGraphTables.all(model));
    ClassOverviewGenerator.CALL_NOTES.forEach(note -> builder.add(new Note(note)));
  }

  private static void configuration(Builder builder, ProjectModel model) {
    builder.chapter("設定");
    builder.section("@Bean 定義");
    builder.table("@Bean 定義", ConfigurationReportGenerator.beans(model));
    builder.add(new Note("Bean 名は明記されたもののみ記載し、既定名は推測しない。Bean の内容は解釈しない。"));
    builder.section("@ConfigurationProperties");
    builder.table("@ConfigurationProperties", ConfigurationReportGenerator.properties(model));
    builder.section("設定クラス（@Configuration）");
    builder.table("設定クラス", ConfigurationReportGenerator.configurationClasses(model));
  }

  private static void limits(Builder builder, ProjectModel model) {
    builder.chapter("解析上の制約");
    builder.section("解析エラー");
    builder.table("解析エラー", IssueReportGenerator.table(model, null, null));
    builder.section("静的解析で検出しない事項");
    builder.add(new BulletList(LIMITS));
  }

  private static String entityHeading(EntityModel entity) {
    return Labels.simpleName(entity.typeName())
        + "（"
        + Labels.entityKind(entity.kind())
        + (entity.kind() == EntityKind.ENTITY ? "：" + Labels.orUnspecified(entity.tableName()) : "")
        + "）";
  }

  /** Collects blocks and numbers chapters, sections, subsections, figures and tables. */
  private static final class Builder {
    private final List<Block> blocks = new ArrayList<>();
    private int chapter;
    private int section;
    private int subsection;
    private int figure;
    private int table;

    /** Numbers of the entity subsections in 5.3 and 5.4, by type name, for 6.2 to refer to. */
    private final Map<String, String> columnSections = new HashMap<>();

    private final Map<String, String> relationSections = new HashMap<>();

    String subsectionNumber() {
      return chapter + "." + section + "." + subsection;
    }

    void add(Block block) {
      blocks.add(block);
    }

    void chapter(String title) {
      chapter++;
      section = 0;
      subsection = 0;
      figure = 0;
      table = 0;
      if (chapter > 1) {
        add(new PageBreak());
      }
      add(new Heading(1, chapter + ". " + title));
    }

    void section(String title) {
      section++;
      subsection = 0;
      add(new Heading(2, chapter + "." + section + " " + title));
    }

    void subsection(String title) {
      subsection++;
      add(new Heading(3, chapter + "." + section + "." + subsection + " " + title));
    }

    /** A numbered table; the caption goes above the table. */
    void table(String caption, Table content) {
      table++;
      add(new Caption("表 " + chapter + "-" + table + " " + caption));
      add(new TableBlock(content));
    }

    /** Numbered figures with captions below, or a "not detected" line when there are none. */
    void figures(List<Diagram> diagrams, Table fallback) {
      if (diagrams.isEmpty()) {
        add(new Paragraph(Section.NOT_DETECTED));
        return;
      }
      for (Diagram diagram : diagrams) {
        figure++;
        add(new DiagramBlock(diagram, fallback));
        add(new Caption("図 " + chapter + "-" + figure + " " + diagram.title()));
      }
    }
  }
}
