package com.example.javaanalyzer.web;

import com.example.javaanalyzer.analysis.ProjectAnalyzer;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.io.IrReader;
import com.example.javaanalyzer.ir.io.IrSchemaVersionException;
import com.example.javaanalyzer.ir.io.IrWriter;
import com.example.javaanalyzer.upload.InspectionResult;
import com.example.javaanalyzer.upload.ProjectStorage;
import com.example.javaanalyzer.upload.UploadResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Connects the upload and analysis steps, which do not know each other: analyzes an uploaded
 * project and saves its IR in the project's workspace folder (FR-04), together with the inspection
 * result and the outcome so that the result screens can be reopened by URL.
 */
@Service
public class ProjectAnalysisService {

  private static final Pattern PROJECT_ID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private static final Logger log = LoggerFactory.getLogger(ProjectAnalysisService.class);

  private static final JsonMapper RESULT_MAPPER =
      JsonMapper.builder()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private final ProjectAnalyzer analyzer;
  private final ProjectStorage storage;

  public ProjectAnalysisService(ProjectAnalyzer analyzer, ProjectStorage storage) {
    this.analyzer = analyzer;
    this.storage = storage;
  }

  /** Analyzes a project that passed inspection and saves the IR. */
  public ProjectModel analyze(UploadResult upload) {
    InspectionResult inspection = upload.inspection();
    ProjectModel model = analyzer.analyze(inspection.projectRoot(), inspection.sourceEncoding());
    IrWriter.write(model, storage.irDir(upload.projectId()));
    return model;
  }

  /**
   * Analyzes the project if the inspection allows it, and saves the outcome. An analysis failure is
   * logged and saved as {@link AnalysisStatus#FAILED} instead of being thrown.
   */
  public AnalysisStatus analyzeAndSave(UploadResult upload) {
    AnalysisStatus status;
    if (!upload.inspection().supported()) {
      status = AnalysisStatus.UNSUPPORTED;
    } else {
      try {
        analyze(upload);
        status = AnalysisStatus.SUCCEEDED;
      } catch (RuntimeException | StackOverflowError e) {
        // A stack overflow (deeply nested source) has unwound by now and is safe to recover from
        log.error("Analysis failed for project {}", upload.projectId(), e);
        status = AnalysisStatus.FAILED;
      }
    }
    saveResult(upload.projectId(), SavedProjectResult.of(upload.inspection(), status));
    return status;
  }

  /**
   * Loads a project saved by {@link #analyzeAndSave}. Empty when the id is not a generated project
   * id or nothing was saved for it.
   */
  public Optional<ProjectResult> load(String projectId) {
    if (!isProjectId(projectId)) {
      return Optional.empty();
    }
    Path resultFile = storage.resultFile(projectId);
    if (!Files.isRegularFile(resultFile)) {
      return Optional.empty();
    }
    SavedProjectResult saved;
    try {
      saved =
          RESULT_MAPPER.readValue(
              Files.readString(resultFile, StandardCharsets.UTF_8), SavedProjectResult.class);
    } catch (IOException | JacksonException e) {
      throw new IllegalStateException("Failed to read " + resultFile, e);
    }
    if (saved.status() != AnalysisStatus.SUCCEEDED) {
      return Optional.of(new ProjectResult(projectId, saved, null, false));
    }
    try {
      return Optional.of(
          new ProjectResult(projectId, saved, IrReader.read(storage.irDir(projectId)), false));
    } catch (IrSchemaVersionException e) {
      return Optional.of(new ProjectResult(projectId, saved, null, true));
    }
  }

  /** Whether the value has the form of a generated project id (a UUID). */
  static boolean isProjectId(String value) {
    return value != null && PROJECT_ID.matcher(value).matches();
  }

  private void saveResult(String projectId, SavedProjectResult result) {
    Path resultFile = storage.resultFile(projectId);
    try {
      Files.createDirectories(resultFile.getParent());
      Files.writeString(
          resultFile, RESULT_MAPPER.writeValueAsString(result), StandardCharsets.UTF_8);
    } catch (IOException | JacksonException e) {
      throw new IllegalStateException("Failed to save " + resultFile, e);
    }
  }

  /**
   * Finds a saved IR file. Both arguments come from the request URL, so anything that is not a
   * generated project id or leaves the project's IR folder is treated as not found.
   *
   * @param relativePath path relative to the IR folder, e.g. {@code project.json}
   */
  public Optional<Path> irFile(String projectId, String relativePath) {
    if (!isProjectId(projectId)) {
      return Optional.empty();
    }
    Path irDir = storage.irDir(projectId).normalize();
    try {
      Path file = irDir.resolve(relativePath).normalize();
      return file.startsWith(irDir) && Files.isRegularFile(file)
          ? Optional.of(file)
          : Optional.empty();
    } catch (InvalidPathException e) {
      return Optional.empty();
    }
  }
}
