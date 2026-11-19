package com.example.javaanalyzer.web;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Serves saved IR JSON files so that a person can read them (FR-04). */
@Controller
public class IrFileController {

  private final ProjectAnalysisService analysisService;

  public IrFileController(ProjectAnalysisService analysisService) {
    this.analysisService = analysisService;
  }

  @GetMapping("/projects/{projectId}/ir/project.json")
  public ResponseEntity<Resource> index(@PathVariable String projectId) {
    return irFile(projectId, "project.json");
  }

  @GetMapping("/projects/{projectId}/ir/packages/{fileName}")
  public ResponseEntity<Resource> packageFile(
      @PathVariable String projectId, @PathVariable String fileName) {
    return irFile(projectId, "packages/" + fileName);
  }

  private ResponseEntity<Resource> irFile(String projectId, String relativePath) {
    return analysisService
        .irFile(projectId, relativePath)
        .<ResponseEntity<Resource>>map(
            file ->
                ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new FileSystemResource(file)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
