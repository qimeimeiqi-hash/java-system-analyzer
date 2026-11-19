package com.example.javaanalyzer.generation.export;

import com.example.javaanalyzer.generation.Section;
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.poi.util.Units;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSimpleField;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;

/**
 * Renders an {@link ExportDocument} as a Word file (FR-11) with Apache POI, on A4 portrait.
 *
 * <p>Headings use real Word heading styles (Heading1 to Heading4, with outline levels) so that Word
 * can build a table of contents and navigation pane. A diagram is the PNG made by the browser
 * (SCR-11); when it is missing or not a PNG, the fallback table and the Mermaid code are written
 * instead and the export still succeeds.
 */
public final class DocxRenderer {

  public static final String DIAGRAM_FAILED = "図を生成できませんでした。表と Mermaid のコードで代替する。";

  /** A4 width 11906 twips minus 2 x 1134 twips (2 cm) margins = 9638 twips = 481.9 pt. */
  static final double TEXT_WIDTH_POINTS = 480;

  private static final int A4_WIDTH_TWIPS = 11906;
  private static final int A4_HEIGHT_TWIPS = 16838;
  private static final int MARGIN_TWIPS = 1134;
  private static final int[] HEADING_SIZES = {16, 13, 11, 10};
  private static final String CODE_FONT = "Consolas";
  private static final String HEADER_FILL = "E8ECF2";

  private DocxRenderer() {}

  /**
   * @param images browser PNGs by diagram id; ids without a usable PNG fall back to code
   */
  public static byte[] render(ExportDocument document, Map<String, byte[]> images) {
    try (XWPFDocument word = new XWPFDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      setUpPage(word);
      addHeadingStyles(word);
      addHeaderAndFooter(word, document.title());
      boolean hasCover = !document.blocks().isEmpty() && document.blocks().get(0) instanceof Cover;
      if (!hasCover) {
        XWPFParagraph title = word.createParagraph();
        title.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun titleRun = title.createRun();
        titleRun.setBold(true);
        titleRun.setFontSize(20);
        titleRun.setText(document.title());
      }
      for (Block block : document.blocks()) {
        write(word, block, images);
      }
      if (document.blocks().stream().anyMatch(TableOfContents.class::isInstance)) {
        // Word fills the table of contents field when the document is opened
        word.enforceUpdateFields();
      }
      word.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write the Word document", e);
    }
  }

  static void write(XWPFDocument word, Block block, Map<String, byte[]> images) {
    switch (block) {
      case Heading heading -> {
        XWPFParagraph paragraph = word.createParagraph();
        paragraph.setStyle("Heading" + Math.min(heading.level(), HEADING_SIZES.length));
        paragraph.createRun().setText(heading.text());
      }
      case Paragraph paragraph -> text(word, paragraph.text(), false);
      case Note note -> text(word, note.text(), true);
      case TableBlock table -> table(word, table.table());
      case KeyValueBlock keyValues -> keyValueTable(word, keyValues);
      case Code code -> code(word, code.text());
      case DiagramBlock diagram -> diagram(word, diagram, images);
      case Cover cover -> cover(word, cover);
      case TableOfContents toc -> tableOfContents(word);
      case PageBreak pageBreak -> word.createParagraph().createRun().addBreak(BreakType.PAGE);
      case Caption caption -> {
        XWPFParagraph paragraph = word.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        paragraph.setKeepNext(true);
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setFontSize(9);
        run.setText(caption.text());
      }
      case BulletList list ->
          list.items()
              .forEach(
                  item -> {
                    XWPFParagraph paragraph = word.createParagraph();
                    paragraph.setIndentationLeft(360);
                    paragraph.setIndentationHanging(240);
                    paragraph.createRun().setText("・" + item);
                  });
    }
  }

  private static void cover(XWPFDocument word, Cover cover) {
    for (int i = 0; i < 8; i++) {
      word.createParagraph();
    }
    XWPFParagraph title = word.createParagraph();
    title.setAlignment(ParagraphAlignment.CENTER);
    title.setSpacingAfter(600);
    XWPFRun titleRun = title.createRun();
    titleRun.setBold(true);
    titleRun.setFontSize(28);
    titleRun.setText(cover.title());
    for (var detail : cover.details()) {
      XWPFParagraph line = word.createParagraph();
      line.setAlignment(ParagraphAlignment.CENTER);
      XWPFRun run = line.createRun();
      run.setFontSize(14);
      run.setText(detail.key() + "：" + detail.value().text());
    }
    for (int i = 0; i < 6; i++) {
      word.createParagraph();
    }
    XWPFParagraph remark = word.createParagraph();
    remark.setAlignment(ParagraphAlignment.CENTER);
    XWPFRun run = remark.createRun();
    run.setFontSize(9);
    run.setColor("57606A");
    run.setText(cover.remark());
  }

  /**
   * A TOC field over heading levels 1 to 3, filled by Word on opening (see enforceUpdateFields).
   */
  private static void tableOfContents(XWPFDocument word) {
    XWPFParagraph title = word.createParagraph();
    XWPFRun titleRun = title.createRun();
    titleRun.setBold(true);
    titleRun.setFontSize(HEADING_SIZES[0]);
    titleRun.setText("目次");
    XWPFParagraph paragraph = word.createParagraph();
    CTSimpleField field = paragraph.getCTP().addNewFldSimple();
    field.setInstr("TOC \\o \"1-3\" \\h \\z \\u");
    field.addNewR().addNewT().setStringValue("（目次は文書を開くと更新される。更新されない場合は、目次を右クリックして「フィールド更新」を選ぶ）");
  }

  /** Document title in the header and the page number in the footer. */
  private static void addHeaderAndFooter(XWPFDocument word, String title) {
    XWPFHeader header = word.createHeader(HeaderFooterType.DEFAULT);
    XWPFParagraph headerParagraph = header.createParagraph();
    headerParagraph.setAlignment(ParagraphAlignment.RIGHT);
    XWPFRun headerRun = headerParagraph.createRun();
    headerRun.setFontSize(8);
    headerRun.setColor("57606A");
    headerRun.setText(title);
    XWPFFooter footer = word.createFooter(HeaderFooterType.DEFAULT);
    XWPFParagraph footerParagraph = footer.createParagraph();
    footerParagraph.setAlignment(ParagraphAlignment.CENTER);
    CTSimpleField page = footerParagraph.getCTP().addNewFldSimple();
    page.setInstr("PAGE");
    page.addNewR().addNewT().setStringValue("1");
  }

  static void text(XWPFDocument word, String text, boolean note) {
    XWPFParagraph paragraph = word.createParagraph();
    XWPFRun run = paragraph.createRun();
    if (note) {
      run.setItalic(true);
      run.setColor("57606A");
      run.setFontSize(9);
    }
    run.setText(text);
  }

  static void table(XWPFDocument word, Table table) {
    if (table.isEmpty()) {
      text(word, Section.NOT_DETECTED, false);
      return;
    }
    XWPFTable wordTable = word.createTable(table.rows().size() + 1, table.headers().size());
    wordTable.setWidth("100%");
    fillRow(wordTable.getRow(0), table.headers(), true);
    List<List<String>> texts = table.texts();
    for (int i = 0; i < texts.size(); i++) {
      fillRow(wordTable.getRow(i + 1), texts.get(i), false);
    }
    word.createParagraph();
  }

  private static void keyValueTable(XWPFDocument word, KeyValueBlock keyValues) {
    if (keyValues.keyValues().isEmpty()) {
      return;
    }
    XWPFTable wordTable = word.createTable(keyValues.keyValues().size(), 2);
    wordTable.setWidth("100%");
    for (int i = 0; i < keyValues.keyValues().size(); i++) {
      var keyValue = keyValues.keyValues().get(i);
      XWPFTableRow row = wordTable.getRow(i);
      cellText(row.getCell(0), keyValue.key(), true);
      cellText(row.getCell(1), keyValue.value().text(), false);
    }
    word.createParagraph();
  }

  private static void fillRow(XWPFTableRow row, List<String> texts, boolean header) {
    for (int i = 0; i < texts.size(); i++) {
      cellText(row.getCell(i), texts.get(i), header);
    }
    if (header) {
      row.setRepeatHeader(true);
    }
  }

  /** Writes text into a cell, one paragraph per line, in a small font. */
  private static void cellText(XWPFTableCell cell, String text, boolean header) {
    if (header) {
      cell.setColor(HEADER_FILL);
    }
    String[] lines = text.split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      XWPFParagraph paragraph = i == 0 ? cell.getParagraphs().get(0) : cell.addParagraph();
      paragraph.setSpacingAfter(0);
      XWPFRun run = paragraph.createRun();
      run.setFontSize(9);
      run.setBold(header);
      run.setText(lines[i]);
    }
  }

  static void code(XWPFDocument word, String text) {
    for (String line : text.split("\\R", -1)) {
      XWPFParagraph paragraph = word.createParagraph();
      paragraph.setSpacingAfter(0);
      XWPFRun run = paragraph.createRun();
      run.setFontFamily(CODE_FONT);
      run.setFontSize(8);
      run.setText(line);
    }
    word.createParagraph();
  }

  static void diagram(XWPFDocument word, DiagramBlock block, Map<String, byte[]> images) {
    Optional<PngImage> image = PngImage.parse(images.get(block.diagram().id()));
    if (image.isPresent()) {
      XWPFParagraph paragraph = word.createParagraph();
      paragraph.setAlignment(ParagraphAlignment.CENTER);
      double[] size = image.get().pointSize(TEXT_WIDTH_POINTS);
      try {
        paragraph
            .createRun()
            .addPicture(
                new ByteArrayInputStream(image.get().data()),
                XWPFDocument.PICTURE_TYPE_PNG,
                block.diagram().id() + ".png",
                Units.toEMU(size[0]),
                Units.toEMU(size[1]));
      } catch (IOException | org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
        throw new IllegalStateException("Failed to embed diagram " + block.diagram().id(), e);
      }
      return;
    }
    text(word, DIAGRAM_FAILED, true);
    if (block.fallback() != null) {
      table(word, block.fallback());
    }
    code(word, block.diagram().code().stripTrailing());
  }

  private static void setUpPage(XWPFDocument word) {
    CTSectPr section = word.getDocument().getBody().addNewSectPr();
    CTPageSz pageSize = section.addNewPgSz();
    pageSize.setW(BigInteger.valueOf(A4_WIDTH_TWIPS));
    pageSize.setH(BigInteger.valueOf(A4_HEIGHT_TWIPS));
    CTPageMar margin = section.addNewPgMar();
    margin.setTop(BigInteger.valueOf(MARGIN_TWIPS));
    margin.setBottom(BigInteger.valueOf(MARGIN_TWIPS));
    margin.setLeft(BigInteger.valueOf(MARGIN_TWIPS));
    margin.setRight(BigInteger.valueOf(MARGIN_TWIPS));
  }

  /** Heading1 to Heading4 with outline levels, so Word recognizes them as headings. */
  private static void addHeadingStyles(XWPFDocument word) {
    XWPFStyles styles = word.createStyles();
    for (int level = 1; level <= HEADING_SIZES.length; level++) {
      CTStyle style = CTStyle.Factory.newInstance();
      style.setStyleId("Heading" + level);
      style.addNewName().setVal("heading " + level);
      style.addNewBasedOn().setVal("Normal");
      style.addNewNext().setVal("Normal");
      style.addNewQFormat();
      style.addNewUiPriority().setVal(BigInteger.valueOf(9));
      var paragraphProperties = style.addNewPPr();
      paragraphProperties.addNewOutlineLvl().setVal(BigInteger.valueOf(level - 1));
      paragraphProperties.addNewKeepNext();
      var spacing = paragraphProperties.addNewSpacing();
      spacing.setBefore(BigInteger.valueOf(240));
      spacing.setAfter(BigInteger.valueOf(120));
      CTRPr runProperties = style.addNewRPr();
      runProperties.addNewB();
      runProperties.addNewSz().setVal(BigInteger.valueOf(HEADING_SIZES[level - 1] * 2L));
      XWPFStyle wordStyle = new XWPFStyle(style);
      wordStyle.setType(STStyleType.PARAGRAPH);
      styles.addStyle(wordStyle);
    }
  }
}
