package com.example.javaanalyzer.generation.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.analysis.ProjectAnalyzer;
import com.example.javaanalyzer.analysis.plugin.mybatis.MyBatisPlugin;
import com.example.javaanalyzer.analysis.plugin.springboot.SpringBootPlugin;
import com.example.javaanalyzer.analysis.plugin.view.ViewTemplatePlugin;
import com.example.javaanalyzer.generation.ProjectFacts;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.io.IrReader;
import com.example.javaanalyzer.ir.io.IrWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XlsxExporterTest {

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

  private static XSSFWorkbook read(byte[] bytes) throws IOException {
    return new XSSFWorkbook(new ByteArrayInputStream(bytes));
  }

  private static List<String> column(Sheet sheet, int column) {
    DataFormatter formatter = new DataFormatter();
    List<String> values = new ArrayList<>();
    for (Row row : sheet) {
      values.add(row.getCell(column) == null ? "" : formatter.formatCellValue(row.getCell(column)));
    }
    return values;
  }

  private static List<String> row(Sheet sheet, int index) {
    DataFormatter formatter = new DataFormatter();
    List<String> values = new ArrayList<>();
    sheet.getRow(index).forEach(cell -> values.add(formatter.formatCellValue(cell)));
    return values;
  }

  @Test
  @DisplayName("Has the sheets of SCR-11 in order")
  void sheets() throws IOException {
    try (XSSFWorkbook workbook =
        read(XlsxExporter.export(savedIr("lombok-jpa-demo"), FACTS, Map.of()))) {
      List<String> names = new ArrayList<>();
      workbook.forEach(sheet -> names.add(sheet.getSheetName()));
      assertThat(names)
          .containsExactly(
              "統計",
              "API 一覧",
              "エンティティ",
              "カラム",
              "関連",
              "クラス一覧",
              "依存関係",
              "解析エラー",
              "MyBatis",
              "Configuration",
              "画面一覧",
              "図");
    }
  }

  @Test
  @DisplayName("Sheets hold the same rows as the screens; columns and relations name their entity")
  void sheetContents() throws IOException {
    try (XSSFWorkbook workbook =
        read(XlsxExporter.export(savedIr("lombok-jpa-demo"), FACTS, Map.of()))) {
      Sheet api = workbook.getSheet("API 一覧");
      assertThat(row(api, 0))
          .containsExactly("Controller", "Method", "HTTP", "Path", "Request DTO", "Response DTO");
      assertThat(row(api, 1))
          .containsExactly(
              "OrderController",
              "create",
              "POST",
              "/api/orders",
              "CreateOrderRequest",
              "OrderResponse");
      assertThat(api.getPaneInformation().isFreezePane()).isTrue();

      Sheet columns = workbook.getSheet("カラム");
      assertThat(row(columns, 0).get(0)).isEqualTo("エンティティ");
      assertThat(column(columns, 0)).contains("Customer", "PurchaseOrder");

      assertThat(column(workbook.getSheet("統計"), 0)).contains("Spring Boot", "クラス", "Enum");
      assertThat(column(workbook.getSheet("MyBatis"), 0)).contains("検出されませんでした");
    }
  }

  @Test
  @DisplayName("The diagram sheet embeds the PNGs and falls back to Mermaid code for missing ones")
  void diagramSheet() throws IOException {
    byte[] bytes =
        XlsxExporter.export(
            savedIr("lombok-jpa-demo"), FACTS, Map.of("er", TestImages.png(600, 300)));

    try (XSSFWorkbook workbook = read(bytes)) {
      XSSFSheet diagrams = workbook.getSheet("図");
      XSSFDrawing drawing = diagrams.getDrawingPatriarch();
      assertThat(drawing.getShapes()).hasSize(1);
      List<String> texts = column(diagrams, 0);
      assertThat(texts).contains("ER図", "レイヤー構成", "図を生成できませんでした。Mermaid のコードで代替する。");
      assertThat(texts).anyMatch(text -> text.startsWith("flowchart TB"));
    }
  }

  @Test
  @DisplayName(
      "A row with line breaks is as tall as its longest cell; one-line rows keep the default")
  void multiLineRowsAreTaller() throws IOException {
    try (XSSFWorkbook workbook = read(XlsxExporter.export(savedIr("jsp-demo"), FACTS, Map.of()))) {
      XSSFSheet screens = workbook.getSheet("画面一覧");
      // The screens table comes first; the transitions table below repeats the template names
      Row list = null;
      Row detail = null;
      for (Row row : screens) {
        String template = new DataFormatter().formatCellValue(row.getCell(0));
        if (template.equals("items/list.jsp") && list == null) {
          list = row;
        } else if (template.equals("items/detail.jsp") && detail == null) {
          detail = row;
        }
      }

      assertThat(screens.getRow(0).getCell(0).getStringCellValue())
          .isEqualTo("画面（テンプレートの場所：src/main/webapp/WEB-INF/jsp/）");
      assertThat(list.getCell(4).getStringCellValue())
          .isEqualTo("common/header.jspf\ncommon/footer.jsp");
      assertThat(list.getHeightInPoints()).isEqualTo(30f);
      assertThat(detail.getCell(4).getStringCellValue()).isEqualTo("common/footer.jsp");
      assertThat(detail.getHeightInPoints()).isEqualTo(screens.getDefaultRowHeightInPoints());
    }
  }
}
