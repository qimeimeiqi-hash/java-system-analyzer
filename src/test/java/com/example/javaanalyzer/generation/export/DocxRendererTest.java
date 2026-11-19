package com.example.javaanalyzer.generation.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.generation.Cell;
import com.example.javaanalyzer.generation.Diagram;
import com.example.javaanalyzer.generation.KeyValue;
import com.example.javaanalyzer.generation.Table;
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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocxRendererTest {

  private static final Table API =
      new Table(
          List.of("Controller", "Path"),
          List.of(List.of(Cell.of("OrderController"), Cell.of("/api/orders"))));

  private static final Diagram ER = new Diagram("er", "ER図", "erDiagram\n  e1[\"Order\"]\n");

  private static ExportDocument document() {
    return new ExportDocument(
        "成果物一式（com.example）",
        List.of(
            new Heading(1, "API一覧"),
            new Paragraph("概要"),
            new Note("推測しない。"),
            new TableBlock(API),
            new Heading(2, "詳細"),
            new KeyValueBlock(List.of(KeyValue.of("テーブル名", "未指定"))),
            new Code("SELECT *\nFROM orders"),
            new TableBlock(new Table(List.of("A"), List.of())),
            new DiagramBlock(ER, API)));
  }

  private static XWPFDocument read(byte[] bytes) throws IOException {
    return new XWPFDocument(new ByteArrayInputStream(bytes));
  }

  private static List<String> texts(XWPFDocument document) {
    return document.getParagraphs().stream().map(XWPFParagraph::getText).toList();
  }

  @Test
  @DisplayName("Writes the title and headings with Word heading styles, and tables with a header")
  void writesHeadingsAndTables() throws IOException {
    try (XWPFDocument document = read(DocxRenderer.render(document(), Map.of()))) {
      assertThat(document.getParagraphs())
          .filteredOn(p -> p.getStyle() != null && p.getStyle().startsWith("Heading"))
          .extracting(p -> p.getStyle() + ":" + p.getText())
          .containsExactly("Heading1:API一覧", "Heading2:詳細");
      assertThat(document.getStyles().styleExist("Heading1")).isTrue();
      assertThat(texts(document))
          .contains("成果物一式（com.example）", "概要", "推測しない。", "SELECT *", "FROM orders", "検出されませんでした");

      XWPFTable api = document.getTables().get(0);
      assertThat(api.getRow(0).getCell(0).getText()).isEqualTo("Controller");
      assertThat(api.getRow(1).getCell(1).getText()).isEqualTo("/api/orders");
      XWPFTable keyValues = document.getTables().get(1);
      assertThat(keyValues.getRow(0).getCell(0).getText()).isEqualTo("テーブル名");
      assertThat(keyValues.getRow(0).getCell(1).getText()).isEqualTo("未指定");
    }
  }

  @Test
  @DisplayName("Embeds the browser PNG of a diagram; captions come from Caption blocks only")
  void embedsDiagramImage() throws IOException {
    byte[] bytes = DocxRenderer.render(document(), Map.of("er", TestImages.png(800, 400)));

    try (XWPFDocument document = read(bytes)) {
      assertThat(document.getAllPictures()).hasSize(1);
      assertThat(texts(document)).doesNotContain("ER図", DIAGRAM_FAILED);
    }
  }

  private static final String DIAGRAM_FAILED = DocxRenderer.DIAGRAM_FAILED;

  private static ExportDocument formalDocument() {
    return new ExportDocument(
        "基本設計書",
        List.of(
            new Cover("基本設計書", List.of(KeyValue.of("システム名", "（手動記入欄）")), "自動生成した。"),
            new PageBreak(),
            new TableOfContents(),
            new Heading(1, "1. システム概要"),
            new Heading(4, "依存先"),
            new Caption("表 1-1 API 一覧"),
            new BulletList(List.of("推測しない。", "実行しない。"))));
  }

  @Test
  @DisplayName("A formal document has a cover, a TOC field, page breaks and Heading4")
  void formalDocumentBlocks() throws IOException {
    try (XWPFDocument document = read(DocxRenderer.render(formalDocument(), Map.of()))) {
      assertThat(texts(document))
          .contains("基本設計書", "システム名：（手動記入欄）", "自動生成した。", "目次", "表 1-1 API 一覧")
          .contains("・推測しない。", "・実行しない。");
      assertThat(texts(document).stream().filter("基本設計書"::equals)).as("no extra title").hasSize(1);
      assertThat(document.getParagraphs())
          .anyMatch(
              p ->
                  p.getCTP().getFldSimpleList().stream()
                      .anyMatch(f -> f.getInstr().startsWith("TOC")))
          .anyMatch(p -> p.getRuns().stream().anyMatch(r -> r.getCTR().getBrList().size() > 0))
          .anyMatch(p -> "Heading4".equals(p.getStyle()) && p.getText().equals("依存先"));
      String settings =
          new String(
              document
                  .getPackage()
                  .getPartsByName(java.util.regex.Pattern.compile("/word/settings.xml"))
                  .get(0)
                  .getInputStream()
                  .readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8);
      assertThat(settings).as("Word updates the TOC on opening").contains("updateFields");
    }
  }

  @Test
  @DisplayName("Every page has the title in the header and a page number field in the footer")
  void headerAndFooter() throws IOException {
    try (XWPFDocument document = read(DocxRenderer.render(formalDocument(), Map.of()))) {
      assertThat(document.getHeaderList()).anyMatch(h -> h.getText().contains("基本設計書"));
      assertThat(document.getFooterList())
          .anyMatch(
              f ->
                  f.getParagraphs().stream()
                      .anyMatch(
                          p ->
                              p.getCTP().getFldSimpleList().stream()
                                  .anyMatch(field -> field.getInstr().equals("PAGE"))));
    }
  }

  @Test
  @DisplayName("Without a usable PNG, shows the note, the fallback table and the Mermaid code")
  void fallsBackWithoutImage() throws IOException {
    byte[] notPng = "<svg/>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    for (Map<String, byte[]> images : List.of(Map.<String, byte[]>of(), Map.of("er", notPng))) {
      try (XWPFDocument document = read(DocxRenderer.render(document(), images))) {
        assertThat(document.getAllPictures()).isEmpty();
        assertThat(texts(document))
            .contains("図を生成できませんでした。表と Mermaid のコードで代替する。")
            .contains("erDiagram", "  e1[\"Order\"]");
        assertThat(document.getTables()).hasSize(3);
      }
    }
  }
}
