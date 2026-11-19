package com.example.javaanalyzer.generation.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.generation.Cell;
import com.example.javaanalyzer.generation.Diagram;
import com.example.javaanalyzer.generation.KeyValue;
import com.example.javaanalyzer.generation.Table;
import com.example.javaanalyzer.generation.export.Block.Code;
import com.example.javaanalyzer.generation.export.Block.DiagramBlock;
import com.example.javaanalyzer.generation.export.Block.Heading;
import com.example.javaanalyzer.generation.export.Block.KeyValueBlock;
import com.example.javaanalyzer.generation.export.Block.Note;
import com.example.javaanalyzer.generation.export.Block.Paragraph;
import com.example.javaanalyzer.generation.export.Block.TableBlock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarkdownRendererTest {

  @Test
  @DisplayName("Renders headings, paragraphs, notes, tables, key-values, code and Mermaid blocks")
  void rendersEveryBlock() {
    ExportDocument document =
        new ExportDocument(
            "成果物一式",
            List.of(
                new Heading(1, "API一覧"),
                new Paragraph("概要です。"),
                new Note("推測しない。"),
                new TableBlock(
                    new Table(
                        List.of("Controller", "Path"),
                        List.of(List.of(Cell.of("OrderController"), Cell.of("/a|b"))))),
                new Heading(2, "詳細"),
                new KeyValueBlock(List.of(KeyValue.of("テーブル名", "未指定"))),
                new Code("SELECT *\nFROM t"),
                new DiagramBlock(new Diagram("er", "ER図", "erDiagram\n  e1[\"A\"]\n"), null)));

    assertThat(MarkdownRenderer.render(document))
        .isEqualTo(
            """
            # 成果物一式

            ## API一覧

            概要です。

            > 推測しない。

            | Controller | Path |
            | --- | --- |
            | OrderController | /a\\|b |

            ### 詳細

            | 項目 | 内容 |
            | --- | --- |
            | テーブル名 | 未指定 |

            ```
            SELECT *
            FROM t
            ```

            **ER図**

            ```mermaid
            erDiagram
              e1["A"]
            ```

            """);
  }

  @Test
  @DisplayName("An empty table says 検出されませんでした; line breaks in cells do not break the row")
  void emptyTableAndLineBreaks() {
    String markdown =
        MarkdownRenderer.render(
            new ExportDocument(
                "t",
                List.of(
                    new TableBlock(new Table(List.of("A"), List.of())),
                    new TableBlock(
                        new Table(List.of("Javadoc"), List.of(List.of(Cell.of("1行目\n2行目"))))))));

    assertThat(markdown).contains("検出されませんでした\n").contains("| 1行目<br>2行目 |");
  }

  @Test
  @DisplayName("Cover, captions and bullets are text; TOC and page breaks have no Markdown form")
  void formalBlocks() {
    String markdown =
        MarkdownRenderer.render(
            new ExportDocument(
                "基本設計書",
                List.of(
                    new Block.Cover("基本設計書", List.of(KeyValue.of("作成日", "2026/11/11")), "自動生成した。"),
                    new Block.PageBreak(),
                    new Block.TableOfContents(),
                    new Block.Caption("表 4-1 API 一覧"),
                    new Block.BulletList(List.of("推測しない。", "実行しない。")))));

    assertThat(markdown)
        .isEqualTo(
            """
            # 基本設計書

            作成日：2026/11/11 \s

            > 自動生成した。

            *表 4-1 API 一覧*

            - 推測しない。
            - 実行しない。

            """);
  }

  @Test
  @DisplayName("Code containing a backtick fence gets a longer fence so it cannot end the block")
  void codeWithBackticks() {
    String markdown =
        MarkdownRenderer.render(new ExportDocument("t", List.of(new Code("a ``` b"))));

    assertThat(markdown).contains("````\na ``` b\n````\n");
  }

  @Test
  @DisplayName("<, > and & in text and cells are escaped so viewers do not read them as HTML")
  void escapesHtmlCharactersOutsideCode() {
    String markdown =
        MarkdownRenderer.render(
            new ExportDocument(
                "t",
                List.of(
                    new Heading(1, "Map<K, V>"),
                    new Paragraph("<include> & more"),
                    new Note("List<Foo>・Optional<Foo>"),
                    new TableBlock(
                        new Table(
                            List.of("型"), List.of(List.of(Cell.of("List<PurchaseOrder>\nA&B"))))),
                    new Block.BulletList(List.of("<p>x</p>")),
                    new Code("List<Foo> & <bar>"))));

    assertThat(markdown)
        .contains("## Map&lt;K, V&gt;")
        .contains("&lt;include&gt; &amp; more")
        .contains("> List&lt;Foo&gt;・Optional&lt;Foo&gt;")
        .contains("| List&lt;PurchaseOrder&gt;<br>A&amp;B |")
        .contains("- &lt;p&gt;x&lt;/p&gt;")
        .contains("List<Foo> & <bar>");
  }
}
