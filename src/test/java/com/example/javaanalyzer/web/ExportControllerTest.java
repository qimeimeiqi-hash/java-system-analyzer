package com.example.javaanalyzer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.javaanalyzer.analysis.ProjectAnalyzer;
import com.example.javaanalyzer.analysis.plugin.mybatis.MyBatisPlugin;
import com.example.javaanalyzer.analysis.plugin.springboot.SpringBootPlugin;
import com.example.javaanalyzer.analysis.plugin.view.ViewTemplatePlugin;
import com.example.javaanalyzer.generation.Diagram;
import com.example.javaanalyzer.generation.export.Block;
import com.example.javaanalyzer.generation.export.ExportDocument;
import com.example.javaanalyzer.ir.ProjectModel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ExportController.class)
class ExportControllerTest {

  private static final String ID = "3f2b8c1e-7a4d-4f60-9b21-0c5de8a1f7aa";
  private static final String BASE = "/projects/" + ID;
  private static final ProjectModel SHOP =
      new ProjectAnalyzer(
              List.of(new SpringBootPlugin(), new MyBatisPlugin(), new ViewTemplatePlugin()))
          .analyze(Path.of("src/test/resources/sample-projects/lombok-jpa-demo"));

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ProjectAnalysisService analysisService;

  @BeforeEach
  void loadShop() {
    given(analysisService.load(ID))
        .willReturn(
            Optional.of(
                new ProjectResult(
                    ID,
                    new SavedProjectResult(
                        "pom.xml", "3.3.4", "UTF-8", List.of(), AnalysisStatus.SUCCEEDED),
                    SHOP,
                    false)));
  }

  private static byte[] png(int width) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(width, 200, BufferedImage.TYPE_INT_RGB), "png", out);
    return out.toByteArray();
  }

  @Test
  @DisplayName("SCR-11 offers the three formats and carries the Mermaid code of every diagram")
  void exportPage() throws Exception {
    mockMvc
        .perform(get(BASE + "/export"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(BASE + "/export/documents.md")))
        .andExpect(content().string(containsString(BASE + "/export/documents.docx")))
        .andExpect(content().string(containsString(BASE + "/export/documents.xlsx")))
        .andExpect(content().string(containsString("data-diagram-id=\"er\"")))
        .andExpect(content().string(containsString("data-diagram-id=\"layers\"")))
        .andExpect(content().string(containsString("/js/export.js")));
  }

  @Test
  @DisplayName("Markdown is downloaded as a UTF-8 attachment with Mermaid code blocks")
  void markdown() throws Exception {
    byte[] body =
        mockMvc
            .perform(get(BASE + "/export/documents.md"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", containsString("attachment")))
            .andExpect(header().string("Content-Disposition", containsString("documents.md")))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    assertThat(new String(body, StandardCharsets.UTF_8))
        .startsWith("# 成果物一式（com.example.shop）")
        .contains("```mermaid\nerDiagram");
  }

  @Test
  @DisplayName("Word embeds an accepted PNG; unknown ids and non-PNG data are discarded")
  void wordAcceptsOnlyKnownPngs() throws Exception {
    byte[] body =
        mockMvc
            .perform(
                multipart(BASE + "/export/documents.docx")
                    .file(new MockMultipartFile("diagram-er", "er.png", "image/png", png(400)))
                    .file(new MockMultipartFile("diagram-unknown", "x.png", "image/png", png(400)))
                    .file(
                        new MockMultipartFile(
                            "diagram-layers",
                            "layers.png",
                            "image/png",
                            "<svg/>".getBytes(StandardCharsets.UTF_8))))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", containsString("documents.docx")))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(body))) {
      assertThat(document.getAllPictures()).hasSize(1);
      assertThat(document.getParagraphs())
          .anyMatch(p -> p.getText().equals("図を生成できませんでした。表と Mermaid のコードで代替する。"));
    }
  }

  @Test
  @DisplayName("The basic design document is offered on SCR-11 and embeds the posted diagrams")
  void basicDesign() throws Exception {
    mockMvc
        .perform(get(BASE + "/export"))
        .andExpect(content().string(containsString(BASE + "/export/basic-design.docx")));

    byte[] body =
        mockMvc
            .perform(
                multipart(BASE + "/export/basic-design.docx")
                    .file(new MockMultipartFile("diagram-er", "er.png", "image/png", png(400)))
                    .file(new MockMultipartFile("diagram-layers", "l.png", "image/png", png(600))))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", containsString("basic-design.docx")))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(body))) {
      assertThat(document.getAllPictures()).hasSize(2);
      assertThat(document.getParagraphs())
          .anyMatch(p -> p.getText().equals("基本設計書"))
          .anyMatch(p -> "Heading1".equals(p.getStyle()) && p.getText().equals("5. データ設計"))
          .anyMatch(p -> p.getText().equals("図 5-1 ER図"))
          .anyMatch(p -> p.getText().startsWith("作成日："));
    }
  }

  @Test
  @DisplayName("Without any image (no JavaScript), Word and Excel still download with fallbacks")
  void exportsWithoutImages() throws Exception {
    byte[] word =
        mockMvc
            .perform(multipart(BASE + "/export/documents.docx"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    byte[] excel =
        mockMvc
            .perform(multipart(BASE + "/export/documents.xlsx"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", containsString("documents.xlsx")))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(word));
        XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excel))) {
      assertThat(document.getAllPictures()).isEmpty();
      assertThat(workbook.getSheet("図")).isNotNull();
    }
  }

  /** A valid PNG padded with trailing bytes to exactly the given size. */
  private static byte[] pngOfSize(long size) throws Exception {
    byte[] image = png(10);
    byte[] padded = new byte[Math.toIntExact(size)];
    System.arraycopy(image, 0, padded, 0, image.length);
    return padded;
  }

  private static ExportDocument documentWith(String... diagramIds) {
    return new ExportDocument(
        "t",
        java.util.Arrays.stream(diagramIds)
            .<Block>map(id -> new Block.DiagramBlock(new Diagram(id, id, "graph"), null))
            .toList());
  }

  @Test
  @DisplayName("A diagram PNG of exactly the limit is accepted, one byte more is discarded")
  void diagramSizeLimitBoundary() throws Exception {
    MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
    request.addFile(
        new MockMultipartFile(
            "diagram-er", "er.png", "image/png", pngOfSize(ExportController.MAX_DIAGRAM_BYTES)));
    request.addFile(
        new MockMultipartFile(
            "diagram-layers",
            "l.png",
            "image/png",
            pngOfSize(ExportController.MAX_DIAGRAM_BYTES + 1)));

    assertThat(ExportController.acceptedImages(request, documentWith("er", "layers")).keySet())
        .containsExactly("er");
  }

  @Test
  @DisplayName("PNGs beyond the total limit are discarded in diagram order, not the whole export")
  void totalSizeLimitDiscardsLaterDiagrams() throws Exception {
    MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
    // Each below the per-diagram limit; four fit in the total, the fifth does not
    long each = ExportController.MAX_TOTAL_DIAGRAM_BYTES / 4 - 1;
    List<String> ids = List.of("a", "b", "c", "d", "e");
    for (String id : ids) {
      request.addFile(
          new MockMultipartFile("diagram-" + id, id + ".png", "image/png", pngOfSize(each)));
    }

    assertThat(
            ExportController.acceptedImages(request, documentWith(ids.toArray(String[]::new)))
                .keySet())
        .containsExactlyInAnyOrder("a", "b", "c", "d");
  }

  @Test
  @DisplayName("A POST that is not multipart exports without images instead of failing")
  void nonMultipartPostExportsWithoutImages() throws Exception {
    byte[] body =
        mockMvc
            .perform(post(BASE + "/export/documents.docx"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(body))) {
      assertThat(document.getAllPictures()).isEmpty();
    }
  }

  @Test
  @DisplayName("The export page tells the browser the per-diagram and total size limits")
  void exportPageCarriesSizeLimits() throws Exception {
    mockMvc
        .perform(get(BASE + "/export"))
        .andExpect(
            content()
                .string(
                    containsString(
                        "data-max-diagram-bytes=\"" + ExportController.MAX_DIAGRAM_BYTES + "\"")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "data-max-total-bytes=\""
                            + ExportController.MAX_TOTAL_DIAGRAM_BYTES
                            + "\"")));
  }

  @Test
  @DisplayName("Exports of an unknown or unanalyzed project are not available")
  void unavailableProjects() throws Exception {
    given(analysisService.load(ID)).willReturn(Optional.empty());
    mockMvc.perform(get(BASE + "/export/documents.md")).andExpect(status().isNotFound());
    mockMvc.perform(multipart(BASE + "/export/documents.docx")).andExpect(status().isNotFound());
    mockMvc.perform(get(BASE + "/export")).andExpect(status().isNotFound());

    given(analysisService.load(ID))
        .willReturn(
            Optional.of(
                new ProjectResult(
                    ID,
                    new SavedProjectResult(null, null, "UTF-8", List.of(), AnalysisStatus.FAILED),
                    null,
                    false)));
    mockMvc
        .perform(get(BASE + "/export"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(BASE));
  }
}
