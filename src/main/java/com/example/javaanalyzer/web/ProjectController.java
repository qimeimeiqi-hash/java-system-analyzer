package com.example.javaanalyzer.web;

import com.example.javaanalyzer.generation.ProjectStatistics;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Result screens of a saved project (FR-10). Every screen reads the saved IR only. */
@Controller
public class ProjectController {

  static final String NOT_FOUND_VIEW = "project/not-found";

  private final ProjectAnalysisService analysisService;

  public ProjectController(ProjectAnalysisService analysisService) {
    this.analysisService = analysisService;
  }

  /** SCR-02: inspection result (FR-02), statistics (FR-13) and the issue summary. */
  @GetMapping("/projects/{projectId}")
  public String overview(
      @PathVariable String projectId, Model model, HttpServletResponse response) {
    Optional<ProjectResult> loaded = analysisService.load(projectId);
    if (loaded.isEmpty()) {
      response.setStatus(HttpStatus.NOT_FOUND.value());
      return NOT_FOUND_VIEW;
    }
    ProjectResult project = loaded.get();
    model.addAttribute("project", project);
    if (project.saved().status() == AnalysisStatus.FAILED) {
      response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }
    if (project.analyzed()) {
      model.addAttribute("statistics", ProjectStatistics.of(project.model()));
    }
    return "project/overview";
  }
}
