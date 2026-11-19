package com.example.javaanalyzer.upload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Isolated storage of uploaded projects: {@code <workspace>/<projectId>/upload.zip}, the extracted
 * sources under {@code <workspace>/<projectId>/source/} and the saved IR under {@code
 * <workspace>/<projectId>/ir/}, and the inspection and analysis outcome in {@code result.json}.
 */
@Component
public class ProjectStorage {

  private static final String UPLOAD_FILE_NAME = "upload.zip";
  private static final String SOURCE_DIR_NAME = "source";
  private static final String IR_DIR_NAME = "ir";
  private static final String RESULT_FILE_NAME = "result.json";

  private final Path workspaceDir;

  public ProjectStorage(UploadProperties properties) {
    this.workspaceDir = Path.of(properties.workspaceDir()).toAbsolutePath().normalize();
  }

  public Path saveUpload(String projectId, MultipartFile file) {
    Path zipFile = projectDir(projectId).resolve(UPLOAD_FILE_NAME);
    try (InputStream in = file.getInputStream()) {
      Files.createDirectories(zipFile.getParent());
      Files.copy(in, zipFile);
    } catch (IOException e) {
      throw new UploadException("アップロードファイルの保存に失敗しました。", e);
    }
    return zipFile;
  }

  public Path sourceDir(String projectId) {
    return projectDir(projectId).resolve(SOURCE_DIR_NAME);
  }

  public Path irDir(String projectId) {
    return projectDir(projectId).resolve(IR_DIR_NAME);
  }

  /** File recording the inspection result and analysis outcome, so the result can be reopened. */
  public Path resultFile(String projectId) {
    return projectDir(projectId).resolve(RESULT_FILE_NAME);
  }

  /** Deletes the whole project folder; failures are ignored because this is cleanup only. */
  public void delete(String projectId) {
    try {
      FileSystemUtils.deleteRecursively(projectDir(projectId));
    } catch (IOException ignored) {
      // Leftover files do not affect other uploads because each project has its own folder.
    }
  }

  private Path projectDir(String projectId) {
    return workspaceDir.resolve(projectId);
  }
}
