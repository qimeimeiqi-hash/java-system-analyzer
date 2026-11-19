package com.example.javaanalyzer.generation.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.analysis.ProjectAnalyzer;
import com.example.javaanalyzer.analysis.plugin.mybatis.MyBatisPlugin;
import com.example.javaanalyzer.analysis.plugin.springboot.SpringBootPlugin;
import com.example.javaanalyzer.analysis.plugin.view.ViewTemplatePlugin;
import com.example.javaanalyzer.generation.Diagram;
import com.example.javaanalyzer.generation.ProjectFacts;
import com.example.javaanalyzer.generation.export.Block.BulletList;
import com.example.javaanalyzer.generation.export.Block.Caption;
import com.example.javaanalyzer.generation.export.Block.Cover;
import com.example.javaanalyzer.generation.export.Block.DiagramBlock;
import com.example.javaanalyzer.generation.export.Block.Heading;
import com.example.javaanalyzer.generation.export.Block.Note;
import com.example.javaanalyzer.generation.export.Block.Paragraph;
import com.example.javaanalyzer.generation.export.Block.TableBlock;
import com.example.javaanalyzer.generation.export.Block.TableOfContents;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.io.IrReader;
import com.example.javaanalyzer.ir.io.IrWriter;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BasicDesignAssemblerTest {

  private static final ProjectFacts FACTS = new ProjectFacts("3.3.4", "pom.xml", "UTF-8");
  private static final LocalDate CREATED = LocalDate.of(2026, 11, 11);

  @TempDir Path irDir;

  private ProjectModel savedIr(String sample) {
    ProjectModel analyzed =
        new ProjectAnalyzer(
                List.of(new SpringBootPlugin(), new MyBatisPlugin(), new ViewTemplatePlugin()))
            .analyze(Path.of("src/test/resources/sample-projects", sample));
    IrWriter.write(analyzed, irDir);
    return IrReader.read(irDir);
  }

  private ExportDocument shop() {
    return BasicDesignAssembler.document(savedIr("lombok-jpa-demo"), FACTS, CREATED);
  }

  private static List<String> headings(ExportDocument document, int maxLevel) {
    return document.blocks().stream()
        .filter(b -> b instanceof Heading h && h.level() <= maxLevel)
        .map(b -> ((Heading) b).text())
        .toList();
  }

  private static <T extends Block> List<T> blocks(ExportDocument document, Class<T> type) {
    return document.blocks().stream().filter(type::isInstance).map(type::cast).toList();
  }

  /** The blocks after the heading with this text, up to the next heading of level 1 or 2. */
  private static List<Block> under(ExportDocument document, String heading) {
    List<Block> blocks = document.blocks();
    int start = -1;
    for (int i = 0; i < blocks.size(); i++) {
      if (blocks.get(i) instanceof Heading h && h.text().equals(heading)) {
        start = i + 1;
        break;
      }
    }
    assertThat(start).as("heading " + heading).isPositive();
    int end = start;
    while (end < blocks.size() && !(blocks.get(end) instanceof Heading h && h.level() <= 2)) {
      end++;
    }
    return blocks.subList(start, end);
  }

  @Test
  @DisplayName("Follows the confirmed eight-chapter outline with numbered chapters and sections")
  void outline() {
    assertThat(headings(shop(), 2))
        .containsExactly(
            "改訂履歴",
            "1. システム概要",
            "1.1 目的・業務概要",
            "1.2 技術構成",
            "1.3 プロジェクト統計",
            "2. アーキテクチャ",
            "2.1 レイヤー構成",
            "2.2 パッケージ依存",
            "2.3 コンポーネント依存",
            "2.4 依存関係一覧",
            "2.5 解決できなかった依存",
            "3. 画面設計",
            "3.1 画面一覧",
            "3.2 画面遷移図",
            "3.3 画面ごとの遷移先",
            "3.4 Controller と対応付けできなかった View 名",
            "4. API 設計",
            "4.1 API 一覧",
            "5. データ設計",
            "5.1 ER 図",
            "5.2 エンティティ定義",
            "5.3 カラム定義",
            "5.4 関連定義",
            "5.5 MyBatis の SQL 一覧",
            "6. コンポーネント設計",
            "6.1 クラス一覧",
            "6.2 クラス別概要",
            "6.3 簡易呼び出し関係",
            "7. 設定",
            "7.1 @Bean 定義",
            "7.2 @ConfigurationProperties",
            "7.3 設定クラス（@Configuration）",
            "8. 解析上の制約",
            "8.1 解析エラー",
            "8.2 静的解析で検出しない事項");
  }

  @Test
  @DisplayName("Starts with a cover, then revision history and table of contents")
  void frontMatter() {
    ExportDocument document = shop();

    assertThat(document.title()).isEqualTo("基本設計書");
    assertThat(document.blocks().get(0)).isInstanceOf(Cover.class);
    Cover cover = (Cover) document.blocks().get(0);
    assertThat(cover.details())
        .extracting(kv -> kv.key() + "=" + kv.value().text())
        .containsExactly("システム名=（手動記入欄）", "対象=com.example.shop", "作成日=2026/11/11");
    assertThat(cover.remark()).contains("静的解析").contains("自動生成");
    assertThat(blocks(document, TableOfContents.class)).hasSize(1);

    TableBlock revisions = (TableBlock) under(document, "改訂履歴").get(0);
    assertThat(revisions.table().headers()).containsExactly("版", "日付", "内容", "作成者", "承認者");
    assertThat(revisions.table().texts())
        .containsExactly(List.of("1.0", "2026/11/11", "初版（本ツールによる自動生成）", "（手動記入欄）", "（手動記入欄）"));
  }

  @Test
  @DisplayName("Chapter 1 opens with the fixed statement of the intended readers and uses")
  void purposeStatement() {
    assertThat(under(shop(), "1. システム概要"))
        .containsExactly(
            new Paragraph(
                "本書は、本システムに新たに参画する開発者が既存システムの構造を短期間で把握するため、"
                    + "および既存コードを変更する開発者が変更前に関連範囲を確認するために、"
                    + "ソースコードから自動抽出した事実情報に基づいて生成された設計書である。"
                    + "業務ロジックの解釈や処理フローの説明は含まない（詳細設計書の範囲外）。"
                    + "後者の用途で第6章の「6.3 簡易呼び出し関係」を参照する場合は、"
                    + "オーバーロード・多態・外部ライブラリ呼び出しを解決しないヒューリスティックな検出である旨に留意し、"
                    + "網羅的な影響範囲調査の代替とはしないこと。"));
  }

  @Test
  @DisplayName("What cannot be extracted from code is left as a manual entry, not guessed")
  void manualEntries() {
    assertThat(under(shop(), "1.1 目的・業務概要")).containsExactly(new Paragraph("（手動記入欄）"));
  }

  @Test
  @DisplayName("Figures are numbered per chapter below the figure; tables are numbered above")
  void captions() {
    ExportDocument document = shop();
    List<String> captions = blocks(document, Caption.class).stream().map(Caption::text).toList();

    assertThat(captions)
        .contains(
            "図 2-1 レイヤー構成",
            "図 2-2 パッケージ依存",
            "図 2-3 コンポーネント依存",
            "図 5-1 ER図",
            "表 1-1 プロジェクト統計",
            "表 4-1 API 一覧");
    List<Block> api = under(document, "4.1 API 一覧");
    assertThat(api.get(0)).isEqualTo(new Caption("表 4-1 API 一覧"));
    assertThat(api.get(1)).isInstanceOf(TableBlock.class);
    List<Block> layers = under(document, "2.1 レイヤー構成");
    assertThat(layers.get(0)).isInstanceOf(DiagramBlock.class);
    assertThat(layers.get(1)).isEqualTo(new Caption("図 2-1 レイヤー構成"));
  }

  @Test
  @DisplayName("Entities, mappers and classes get numbered subsections; class items are level 4")
  void subsections() {
    ExportDocument document = shop();
    List<String> level3 =
        document.blocks().stream()
            .filter(b -> b instanceof Heading h && h.level() == 3)
            .map(b -> ((Heading) b).text())
            .toList();

    assertThat(level3)
        .contains("5.3.1 Address（Embeddable）", "5.3.3 Customer（エンティティ：customers）")
        .anyMatch(text -> text.matches("6\\.2\\.\\d+ OrderService（Service）"));
    assertThat(document.blocks())
        .anyMatch(b -> b instanceof Heading h && h.level() == 4 && h.text().equals("依存先"));
  }

  @Test
  @DisplayName("The simple call graph and the limits of static analysis are stated")
  void callGraphAndLimits() {
    ExportDocument document = shop();

    TableBlock calls = (TableBlock) under(document, "6.3 簡易呼び出し関係").get(1);
    assertThat(calls.table().texts()).anyMatch(row -> row.get(0).equals("OrderService.find(Long)"));
    BulletList limits =
        (BulletList)
            under(document, "8.2 静的解析で検出しない事項").stream()
                .filter(BulletList.class::isInstance)
                .findFirst()
                .orElseThrow();
    assertThat(limits.items())
        .anyMatch(item -> item.contains("コンパイル"))
        .anyMatch(item -> item.contains("オーバーロードと多態"))
        .anyMatch(item -> item.contains("既定名"));
  }

  @Test
  @DisplayName("Class overviews in 6.2 do not repeat the call graph notes that 6.3 states once")
  void callNotesOnlyInSection63() {
    ExportDocument document = shop();

    List<String> notes =
        blocks(document, Note.class).stream()
            .map(Note::text)
            .filter(text -> text.contains("オーバーロードと多態を解決しない"))
            .toList();
    assertThat(notes).hasSize(1);
    assertThat(under(document, "6.3 簡易呼び出し関係"))
        .anyMatch(b -> b instanceof Note n && n.text().contains("オーバーロードと多態を解決しない"));
  }

  /**
   * The blocks of a level-3 subsection whose heading matches, up to the next heading of level 3.
   */
  private static List<Block> subsection(ExportDocument document, String headingPattern) {
    List<Block> blocks = document.blocks();
    int start = -1;
    for (int i = 0; i < blocks.size(); i++) {
      if (blocks.get(i) instanceof Heading h
          && h.level() == 3
          && h.text().matches(headingPattern)) {
        start = i + 1;
        break;
      }
    }
    assertThat(start).as(headingPattern).isPositive();
    int end = start;
    while (end < blocks.size() && !(blocks.get(end) instanceof Heading h && h.level() <= 3)) {
      end++;
    }
    return blocks.subList(start, end);
  }

  private static List<String> level4(List<Block> blocks) {
    return blocks.stream()
        .filter(b -> b instanceof Heading h && h.level() == 4)
        .map(b -> ((Heading) b).text())
        .toList();
  }

  @Test
  @DisplayName("Entity overviews in 6.2 point to their 5.3 and 5.4 subsections instead of tables")
  void entityOverviewRefersToChapter5() {
    ExportDocument document = shop();

    assertThat(headings(document, 3))
        .contains("5.3.3 Customer（エンティティ：customers）", "5.4.3 Customer（エンティティ：customers）");
    List<Block> customer = subsection(document, "6\\.2\\.\\d+ Customer（Entity）");
    assertThat(customer).contains(new Paragraph("カラムは 5.3.3、関連は 5.4.3 を参照。"));
    assertThat(level4(customer)).contains("テーブル", "対応するリポジトリ").doesNotContain("カラム", "関連");
  }

  @Test
  @DisplayName("Empty field, constructor and method items are left out of the 6.2 overviews")
  void emptyMemberItemsOmitted() {
    List<String> items = level4(subsection(shop(), "6\\.2\\.\\d+ CustomerRepository（Repository）"));

    assertThat(items).contains("被依存").doesNotContain("フィールド", "コンストラクタ", "宣言メソッド");
  }

  @Test
  @DisplayName("The screen list names the template directory once and shows relative paths")
  void templateDirectoryInScreens() {
    ExportDocument document =
        BasicDesignAssembler.document(savedIr("thymeleaf-demo"), FACTS, CREATED);

    List<Block> screens = under(document, "3.1 画面一覧");
    assertThat(screens.get(0)).isEqualTo(new Note("テンプレートの場所：src/main/resources/templates/"));
    TableBlock table = (TableBlock) screens.get(2);
    assertThat(table.table().texts())
        .allMatch(row -> !row.get(0).startsWith("src/main/resources/templates/"));
  }

  @Test
  @DisplayName("Uses the same diagram ids as the document set, so the export page's PNGs fit both")
  void sameDiagramsAsDocuments() {
    ProjectModel model = savedIr("thymeleaf-demo");

    assertThat(BasicDesignAssembler.document(model, FACTS, CREATED).diagrams())
        .extracting(Diagram::id)
        .containsExactlyInAnyOrderElementsOf(
            DocumentsAssembler.documents(model, FACTS).diagrams().stream()
                .map(Diagram::id)
                .toList());
  }

  @Test
  @DisplayName("Empty sections keep their number and say nothing was detected")
  void emptySections() {
    List<Block> screens = under(shop(), "3.2 画面遷移図");

    assertThat(screens).containsExactly(new Paragraph("検出されませんでした"));
  }
}
