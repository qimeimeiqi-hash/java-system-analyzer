package com.example.javaanalyzer.generation.export;

import com.example.javaanalyzer.generation.ApiListGenerator;
import com.example.javaanalyzer.generation.Cell;
import com.example.javaanalyzer.generation.ClassListGenerator;
import com.example.javaanalyzer.generation.ConfigurationReportGenerator;
import com.example.javaanalyzer.generation.DependencyTables;
import com.example.javaanalyzer.generation.Diagram;
import com.example.javaanalyzer.generation.EntityTables;
import com.example.javaanalyzer.generation.IssueReportGenerator;
import com.example.javaanalyzer.generation.KeyValue;
import com.example.javaanalyzer.generation.MyBatisReportGenerator;
import com.example.javaanalyzer.generation.ProjectFacts;
import com.example.javaanalyzer.generation.ProjectStatistics;
import com.example.javaanalyzer.generation.Section;
import com.example.javaanalyzer.generation.Table;
import com.example.javaanalyzer.generation.ViewReportGenerator;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.ProjectModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * The Excel export of SCR-11 (FR-11): one sheet per list, with the same rows as the screens, and a
 * "図" sheet with the diagrams as browser PNGs (Mermaid code where no PNG is usable).
 */
public final class XlsxExporter {

  public static final String DIAGRAM_FAILED = "図を生成できませんでした。Mermaid のコードで代替する。";

  /** Excel's limit on the text of one cell. */
  static final int MAX_CELL_LENGTH = 32_767;

  private static final int MAX_COLUMN_CHARS = 60;
  private static final int DEFAULT_ROW_HEIGHT_POINTS = 15;

  private XlsxExporter() {}

  public static byte[] export(ProjectModel model, ProjectFacts facts, Map<String, byte[]> images) {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Styles styles = new Styles(workbook);
      statistics(workbook, styles, model, facts);
      sheet(
          workbook,
          styles,
          "API 一覧",
          List.of(new Titled(null, ApiListGenerator.table(model, null))));
      sheet(
          workbook,
          styles,
          "エンティティ",
          List.of(new Titled(null, EntityTables.entityList(model.entities()))));
      sheet(workbook, styles, "カラム", List.of(new Titled(null, perEntity(model, true))));
      sheet(workbook, styles, "関連", List.of(new Titled(null, perEntity(model, false))));
      sheet(
          workbook,
          styles,
          "クラス一覧",
          List.of(new Titled(null, ClassListGenerator.table(model, null, null))));
      sheet(workbook, styles, "依存関係", List.of(new Titled(null, DependencyTables.all(model))));
      sheet(
          workbook,
          styles,
          "解析エラー",
          List.of(new Titled(null, IssueReportGenerator.table(model, null, null))));
      sheet(
          workbook,
          styles,
          "MyBatis",
          List.of(new Titled(null, MyBatisReportGenerator.statementTable(model))));
      sheet(
          workbook,
          styles,
          "Configuration",
          List.of(
              new Titled("@Bean 定義", ConfigurationReportGenerator.beans(model)),
              new Titled(
                  "@ConfigurationProperties", ConfigurationReportGenerator.properties(model)),
              new Titled(
                  "設定クラス（@Configuration）",
                  ConfigurationReportGenerator.configurationClasses(model))));
      sheet(
          workbook,
          styles,
          "画面一覧",
          List.of(
              new Titled(
                  ViewReportGenerator.templateDirectoryNote(model)
                      .map(note -> "画面（" + note + "）")
                      .orElse("画面"),
                  ViewReportGenerator.screens(model)),
              new Titled("画面ごとの遷移先", ViewReportGenerator.transitions(model)),
              new Titled("Controller と対応付けできなかった View 名", ViewReportGenerator.unmatched(model))));
      diagrams(workbook, styles, DocumentsAssembler.documents(model, facts).diagrams(), images);
      workbook.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write the Excel workbook", e);
    }
  }

  /** A table with an optional title row above it. */
  private record Titled(String title, Table table) {}

  private static void statistics(
      XSSFWorkbook workbook, Styles styles, ProjectModel model, ProjectFacts facts) {
    List<List<Cell>> rows = new ArrayList<>();
    for (KeyValue keyValue : DocumentsAssembler.factValues(model, facts)) {
      rows.add(List.of(Cell.of(keyValue.key()), keyValue.value()));
    }
    Table factsTable = new Table(List.of("項目", "内容"), rows);
    sheet(
        workbook,
        styles,
        "統計",
        List.of(
            new Titled("事前検査", factsTable),
            new Titled(
                "プロジェクト統計", DocumentsAssembler.statisticsTable(ProjectStatistics.of(model)))));
  }

  /** The columns or relations of every entity in one table, led by the entity name. */
  private static Table perEntity(ProjectModel model, boolean columns) {
    List<String> headers = new ArrayList<>(List.of("エンティティ"));
    List<List<Cell>> rows = new ArrayList<>();
    for (EntityModel entity :
        model.entities().stream().sorted(Comparator.comparing(EntityModel::typeName)).toList()) {
      Table table = columns ? EntityTables.columns(entity) : EntityTables.relations(entity);
      if (headers.size() == 1) {
        headers.addAll(table.headers());
      }
      for (List<Cell> row : table.rows()) {
        List<Cell> withEntity = new ArrayList<>();
        withEntity.add(Cell.typeName(entity.typeName()));
        withEntity.addAll(row);
        rows.add(withEntity);
      }
    }
    if (headers.size() == 1) {
      headers.addAll(columns ? EntityTables.COLUMN_HEADERS : EntityTables.RELATION_HEADERS);
    }
    return new Table(headers, rows);
  }

  private static void sheet(
      XSSFWorkbook workbook, Styles styles, String name, List<Titled> tables) {
    XSSFSheet sheet = workbook.createSheet(name);
    int[] widths = new int[64];
    int rowIndex = 0;
    for (Titled titled : tables) {
      if (titled.title() != null) {
        Row titleRow = sheet.createRow(rowIndex++);
        setText(titleRow.createCell(0), titled.title(), styles.title);
      }
      Table table = titled.table();
      Row header = sheet.createRow(rowIndex);
      if (tables.size() == 1 && titled.title() == null) {
        sheet.createFreezePane(0, rowIndex + 1);
      }
      rowIndex++;
      for (int i = 0; i < table.headers().size(); i++) {
        setText(header.createCell(i), table.headers().get(i), styles.header);
        widths[i] = Math.max(widths[i], displayWidth(table.headers().get(i)));
      }
      if (table.isEmpty()) {
        setText(sheet.createRow(rowIndex++).createCell(0), Section.NOT_DETECTED, styles.text);
      }
      for (List<String> row : table.texts()) {
        Row excelRow = sheet.createRow(rowIndex++);
        int lines = 1;
        for (int i = 0; i < row.size(); i++) {
          setText(excelRow.createCell(i), row.get(i), styles.text);
          widths[i] = Math.max(widths[i], displayWidth(row.get(i)));
          lines = Math.max(lines, row.get(i).split("\\R", -1).length);
        }
        // Excel does not always fit a row to line breaks written by POI, so set the height
        if (lines > 1) {
          excelRow.setHeightInPoints(lines * DEFAULT_ROW_HEIGHT_POINTS);
        }
      }
      rowIndex++;
    }
    for (int i = 0; i < widths.length; i++) {
      if (widths[i] > 0) {
        sheet.setColumnWidth(i, (Math.min(widths[i], MAX_COLUMN_CHARS) + 2) * 256);
      }
    }
  }

  private static void diagrams(
      XSSFWorkbook workbook, Styles styles, List<Diagram> diagrams, Map<String, byte[]> images) {
    XSSFSheet sheet = workbook.createSheet("図");
    sheet.setColumnWidth(0, 100 * 256);
    XSSFDrawing drawing = null;
    int rowIndex = 0;
    for (Diagram diagram : diagrams) {
      setText(sheet.createRow(rowIndex++).createCell(0), diagram.title(), styles.title);
      Optional<PngImage> image = PngImage.parse(images.get(diagram.id()));
      if (image.isPresent()) {
        if (drawing == null) {
          drawing = sheet.createDrawingPatriarch();
        }
        int pictureIndex = workbook.addPicture(image.get().data(), Workbook.PICTURE_TYPE_PNG);
        XSSFClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
        anchor.setCol1(0);
        anchor.setRow1(rowIndex);
        drawing.createPicture(anchor, pictureIndex).resize(1 / PngImage.RENDER_SCALE);
        double heightPoints = image.get().pointSize(Double.MAX_VALUE)[1];
        rowIndex += (int) Math.ceil(heightPoints / DEFAULT_ROW_HEIGHT_POINTS) + 2;
      } else {
        setText(sheet.createRow(rowIndex++).createCell(0), DIAGRAM_FAILED, styles.text);
        setText(sheet.createRow(rowIndex++).createCell(0), diagram.code(), styles.code);
        rowIndex++;
      }
    }
    if (diagrams.isEmpty()) {
      setText(sheet.createRow(0).createCell(0), Section.NOT_DETECTED, styles.text);
    }
  }

  private static void setText(org.apache.poi.ss.usermodel.Cell cell, String text, CellStyle style) {
    String value =
        text.length() > MAX_CELL_LENGTH
            ? text.substring(0, MAX_CELL_LENGTH - 20) + "…（以下省略）"
            : text;
    cell.setCellValue(value);
    cell.setCellStyle(style);
  }

  /** Approximate width in characters: full-width characters count as two. */
  private static int displayWidth(String text) {
    int longest = 0;
    for (String line : text.split("\\R")) {
      int width = 0;
      for (int i = 0; i < line.length(); i++) {
        width += line.charAt(i) > 0xFF ? 2 : 1;
      }
      longest = Math.max(longest, width);
    }
    return longest;
  }

  /** Cell styles of the workbook; a workbook has a limited number of styles, so they are shared. */
  private static final class Styles {
    final CellStyle header;
    final CellStyle title;
    final CellStyle text;
    final CellStyle code;

    Styles(XSSFWorkbook workbook) {
      Font bold = workbook.createFont();
      bold.setBold(true);
      header = workbook.createCellStyle();
      header.setFont(bold);
      header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
      header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      header.setVerticalAlignment(VerticalAlignment.TOP);
      title = workbook.createCellStyle();
      Font large = workbook.createFont();
      large.setBold(true);
      large.setFontHeightInPoints((short) 12);
      title.setFont(large);
      text = workbook.createCellStyle();
      text.setWrapText(true);
      text.setVerticalAlignment(VerticalAlignment.TOP);
      code = workbook.createCellStyle();
      code.setWrapText(true);
      code.setVerticalAlignment(VerticalAlignment.TOP);
      Font monospace = workbook.createFont();
      monospace.setFontName("Consolas");
      code.setFont(monospace);
    }
  }
}
