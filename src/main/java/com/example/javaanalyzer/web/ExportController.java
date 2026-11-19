package com.example.javaanalyzer.web;

import com.example.javaanalyzer.generation.Diagram;
import com.example.javaanalyzer.generation.ProjectFacts;
import com.example.javaanalyzer.generation.export.BasicDesignAssembler;
import com.example.javaanalyzer.generation.export.DocumentsAssembler;
import com.example.javaanalyzer.generation.export.DocxRenderer;
import com.example.javaanalyzer.generation.export.ExportDocument;
import com.example.javaanalyzer.generation.export.MarkdownRenderer;
import com.example.javaanalyzer.generation.export.PngImage;
import com.example.javaanalyzer.generation.export.XlsxExporter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * SCR-11: exports of the analysis results (FR-11) and the basic design document (FR-19). Word and
 * Excel are POSTed with the diagrams as PNGs that the browser rendered with Mermaid (SCR-11
 * "図の埋め込み方式"); without them, or for a diagram whose PNG is unusable, the documents fall back to
 * tables and Mermaid code.
 */
@Controller
public class ExportController {

  /** Name prefix of the multipart parts that carry diagram PNGs: {@code diagram-<id>}. */
  static final String DIAGRAM_PART_PREFIX = "diagram-";

  /** A diagram PNG larger than this is discarded. */
  static final long MAX_DIAGRAM_BYTES = 10L * 1024 * 1024;

  /**
   * PNGs of one export together; kept below the multipart request limit (50MB in
   * application.properties) so that large diagrams fall back to tables instead of the whole export
   * failing. The browser stops sending at this total too (export.js).
   */
  static final long MAX_TOTAL_DIAGRAM_BYTES = 40L * 1024 * 1024;

  static final MediaType DOCX =
      MediaType.parseMediaType(
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
  static final MediaType XLSX =
      MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  private static final Logger log = LoggerFactory.getLogger(ExportController.class);

  private final ProjectAnalysisService analysisService;

  public ExportController(ProjectAnalysisService analysisService) {
    this.analysisService = analysisService;
  }

  @GetMapping("/projects/{projectId}/export")
  public String exportPage(
      @PathVariable String projectId, Model model, HttpServletResponse response) {
    Optional<ProjectResult> loaded = analysisService.load(projectId);
    if (loaded.isEmpty()) {
      response.setStatus(HttpStatus.NOT_FOUND.value());
      return ProjectController.NOT_FOUND_VIEW;
    }
    if (!loaded.get().analyzed()) {
      return "redirect:/projects/" + projectId;
    }
    model.addAttribute("project", loaded.get());
    model.addAttribute("diagrams", documents(loaded.get()).diagrams());
    model.addAttribute("maxDiagramBytes", MAX_DIAGRAM_BYTES);
    model.addAttribute("maxTotalDiagramBytes", MAX_TOTAL_DIAGRAM_BYTES);
    return "project/export";
  }

  @GetMapping("/projects/{projectId}/export/documents.md")
  public ResponseEntity<byte[]> markdown(@PathVariable String projectId) {
    return analyzed(projectId)
        .map(
            project ->
                download(
                    MarkdownRenderer.render(documents(project)).getBytes(StandardCharsets.UTF_8),
                    new MediaType("text", "markdown", StandardCharsets.UTF_8),
                    "documents.md"))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PostMapping("/projects/{projectId}/export/documents.docx")
  public ResponseEntity<byte[]> word(@PathVariable String projectId, HttpServletRequest request) {
    return analyzed(projectId)
        .map(
            project -> {
              ExportDocument document = documents(project);
              return download(
                  DocxRenderer.render(document, acceptedImages(request, document)),
                  DOCX,
                  "documents.docx");
            })
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /** The basic design document (FR-19), with the same browser PNGs as the documents. */
  @PostMapping("/projects/{projectId}/export/basic-design.docx")
  public ResponseEntity<byte[]> basicDesign(
      @PathVariable String projectId, HttpServletRequest request) {
    return analyzed(projectId)
        .map(
            project -> {
              ExportDocument document =
                  BasicDesignAssembler.document(project.model(), facts(project), LocalDate.now());
              return download(
                  DocxRenderer.render(document, acceptedImages(request, document)),
                  DOCX,
                  "basic-design.docx");
            })
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PostMapping("/projects/{projectId}/export/documents.xlsx")
  public ResponseEntity<byte[]> excel(@PathVariable String projectId, HttpServletRequest request) {
    return analyzed(projectId)
        .map(
            project ->
                download(
                    XlsxExporter.export(
                        project.model(),
                        facts(project),
                        acceptedImages(request, documents(project))),
                    XLSX,
                    "documents.xlsx"))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  static ProjectFacts facts(ProjectResult project) {
    SavedProjectResult saved = project.saved();
    return new ProjectFacts(saved.springBootVersion(), saved.pomPath(), saved.sourceEncoding());
  }

  /**
   * The uploaded PNGs that belong to a diagram of this document. Unknown ids, data that is not a
   * PNG, oversized parts and parts beyond the total limit (in diagram order) are discarded; the
   * image content itself is not interpreted (SCR-11). A request that is not multipart carries no
   * images, so every diagram falls back to its table and code.
   */
  static Map<String, byte[]> acceptedImages(HttpServletRequest request, ExportDocument document) {
    if (!(request instanceof MultipartHttpServletRequest multipart)) {
      return Map.of();
    }
    Map<String, byte[]> accepted = new HashMap<>();
    long total = 0;
    for (Diagram diagram : document.diagrams()) {
      String name = DIAGRAM_PART_PREFIX + diagram.id();
      MultipartFile file = multipart.getFile(name);
      if (file == null) {
        continue;
      }
      if (file.getSize() > MAX_DIAGRAM_BYTES || total + file.getSize() > MAX_TOTAL_DIAGRAM_BYTES) {
        log.debug("Discarded upload part {}", name);
        continue;
      }
      String id = diagram.id();
      try {
        byte[] bytes = file.getBytes();
        if (PngImage.parse(bytes).isPresent()) {
          accepted.put(id, bytes);
          total += bytes.length;
        }
      } catch (IOException e) {
        log.warn("Could not read diagram image {}", name, e);
      }
    }
    return accepted;
  }

  private Optional<ProjectResult> analyzed(String projectId) {
    return analysisService.load(projectId).filter(ProjectResult::analyzed);
  }

  private static ExportDocument documents(ProjectResult project) {
    return DocumentsAssembler.documents(project.model(), facts(project));
  }

  private static ResponseEntity<byte[]> download(byte[] body, MediaType type, String fileName) {
    return ResponseEntity.ok()
        .contentType(type)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(body);
  }
}
