package com.example.javaanalyzer.web;

import com.example.javaanalyzer.upload.ProjectUploadService;
import com.example.javaanalyzer.upload.UploadException;
import com.example.javaanalyzer.upload.UploadResult;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * Receives the project zip (FR-01), then has it inspected (FR-02), analyzed and saved (FR-03,
 * FR-04). The result is shown by {@link ProjectController}.
 */
@Controller
public class UploadController {

  private final ProjectUploadService uploadService;
  private final ProjectAnalysisService analysisService;

  public UploadController(
      ProjectUploadService uploadService, ProjectAnalysisService analysisService) {
    this.uploadService = uploadService;
    this.analysisService = analysisService;
  }

  /** On success redirects to SCR-02 (PRG) so that reloading does not upload the zip again. */
  @PostMapping("/upload")
  public String upload(
      @RequestParam(name = "file", required = false) MultipartFile file,
      Model model,
      HttpServletResponse response) {
    UploadResult result;
    try {
      result = uploadService.upload(file);
    } catch (UploadException e) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("errorMessage", e.getMessage());
      return "index";
    }
    analysisService.analyzeAndSave(result);
    return "redirect:/projects/" + result.projectId();
  }
}
