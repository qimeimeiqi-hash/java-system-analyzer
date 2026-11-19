package com.example.javaanalyzer.upload;

import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** FR-01/FR-02: stores an uploaded zip in an isolated workspace, extracts it and inspects it. */
@Service
public class ProjectUploadService {

  private final ProjectStorage storage;
  private final ZipExtractor extractor;
  private final ProjectInspector inspector;

  public ProjectUploadService(
      ProjectStorage storage, ZipExtractor extractor, ProjectInspector inspector) {
    this.storage = storage;
    this.extractor = extractor;
    this.inspector = inspector;
  }

  public UploadResult upload(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new UploadException("ファイルが選択されていないか、空です。");
    }
    String filename = file.getOriginalFilename();
    if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
      throw new UploadException("zip形式のファイルを指定してください。");
    }

    String projectId = UUID.randomUUID().toString();
    try {
      Path zipFile = storage.saveUpload(projectId, file);
      Path sourceDir = storage.sourceDir(projectId);
      extractor.extract(zipFile, sourceDir);
      return new UploadResult(projectId, inspector.inspect(sourceDir));
    } catch (RuntimeException e) {
      storage.delete(projectId);
      throw e;
    }
  }
}
