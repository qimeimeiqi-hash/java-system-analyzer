package com.example.javaanalyzer.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies only that the poi-ooxml dependency works; not production logic. */
class PoiSmokeTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName(
      "Writing one cell to a new workbook produces an .xlsx file that reads back the value")
  void writesWorkbookWithOneCellToTempFile() throws Exception {
    Path outputFile = tempDir.resolve("smoke.xlsx");

    try (Workbook workbook = new XSSFWorkbook();
        OutputStream out = Files.newOutputStream(outputFile)) {
      workbook.createSheet("Sheet1").createRow(0).createCell(0).setCellValue("hello");
      workbook.write(out);
    }

    assertThat(outputFile).exists();
    assertThat(Files.size(outputFile)).isPositive();
    try (InputStream in = Files.newInputStream(outputFile);
        Workbook reloaded = new XSSFWorkbook(in)) {
      assertThat(reloaded.getSheet("Sheet1").getRow(0).getCell(0).getStringCellValue())
          .isEqualTo("hello");
    }
  }
}
