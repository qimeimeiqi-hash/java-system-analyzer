package com.example.javaanalyzer.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

class ProjectUploadServiceTest {

  @TempDir Path tempDir;

  private Path workspaceDir;
  private ProjectUploadService service;

  @BeforeEach
  void setUp() {
    workspaceDir = tempDir.resolve("workspace");
    UploadProperties properties =
        new UploadProperties(workspaceDir.toString(), DataSize.ofMegabytes(1), 100);
    service =
        new ProjectUploadService(
            new ProjectStorage(properties), new ZipExtractor(properties), new ProjectInspector());
  }

  private static MockMultipartFile zipFile(String filename, Map<String, String> entries)
      throws Exception {
    return new MockMultipartFile(
        "file", filename, "application/zip", ZipTestSupport.zipBytes(entries));
  }

  @Test
  @DisplayName("A project zipped with backslash separators is extracted and judged supported")
  void uploadsBackslashSeparatedZip() throws Exception {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("demo\\src\\", "");
    entries.put("demo\\pom.xml", ZipTestSupport.springBootParentPom("3.2.5"));
    entries.put("demo\\src\\main\\java\\com\\example\\App.java", "class App {}");

    UploadResult result = service.upload(zipFile("demo.zip", entries));

    assertThat(result.inspection().supported()).isTrue();
    assertThat(result.inspection().pomPath()).isEqualTo("demo/pom.xml");
    assertThat(result.inspection().issues()).isEmpty();
  }

  @Test
  @DisplayName(
      "Stores the zip in an isolated workspace, extracts it and returns a supported result")
  void uploadsExtractsAndInspects() throws Exception {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("demo/pom.xml", ZipTestSupport.springBootParentPom("3.2.5"));
    entries.put("demo/src/main/java/com/example/DemoApplication.java", "class DemoApplication {}");

    UploadResult result = service.upload(zipFile("demo.zip", entries));

    Path projectDir = workspaceDir.resolve(result.projectId());
    assertThat(projectDir.resolve("upload.zip")).isRegularFile();
    assertThat(projectDir.resolve("source/demo/pom.xml")).isRegularFile();
    assertThat(result.inspection().supported()).isTrue();
    assertThat(result.inspection().springBootVersion()).isEqualTo("3.2.5");
    assertThat(result.inspection().projectRoot())
        .isEqualTo(projectDir.resolve("source/demo").toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("Each upload gets its own project id and workspace directory")
  void eachUploadGetsUniqueWorkspace() throws Exception {
    Map<String, String> entries = Map.of("pom.xml", ZipTestSupport.springBootParentPom("3.2.5"));

    UploadResult first = service.upload(zipFile("a.zip", entries));
    UploadResult second = service.upload(zipFile("b.zip", entries));

    assertThat(first.projectId()).isNotEqualTo(second.projectId());
    assertThat(workspaceDir.resolve(first.projectId())).isDirectory();
    assertThat(workspaceDir.resolve(second.projectId())).isDirectory();
  }

  @Test
  @DisplayName("An unsupported project is still extracted and returned with its reasons")
  void returnsUnsupportedResultForNonSpringBootProject() throws Exception {
    UploadResult result = service.upload(zipFile("plain.zip", Map.of("README.md", "no pom here")));

    assertThat(result.inspection().supported()).isFalse();
    assertThat(result.inspection().issues()).isNotEmpty();
  }

  @Test
  @DisplayName("Rejects an empty file without creating a workspace")
  void rejectsEmptyFile() {
    MockMultipartFile empty =
        new MockMultipartFile("file", "demo.zip", "application/zip", new byte[0]);

    assertThatThrownBy(() -> service.upload(empty))
        .isInstanceOf(UploadException.class)
        .hasMessageContaining("空");
    assertThat(workspaceDir).doesNotExist();
  }

  @Test
  @DisplayName("Rejects a missing file (null)")
  void rejectsMissingFile() {
    assertThatThrownBy(() -> service.upload(null)).isInstanceOf(UploadException.class);
  }

  @Test
  @DisplayName("Rejects a file whose name does not end with .zip")
  void rejectsNonZipExtension() {
    MockMultipartFile text =
        new MockMultipartFile(
            "file", "demo.tar.gz", "application/gzip", "data".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> service.upload(text))
        .isInstanceOf(UploadException.class)
        .hasMessageContaining("zip");
    assertThat(workspaceDir).doesNotExist();
  }

  @Test
  @DisplayName("Accepts an upper-case .ZIP extension")
  void acceptsUpperCaseZipExtension() throws Exception {
    UploadResult result =
        service.upload(
            zipFile("DEMO.ZIP", Map.of("pom.xml", ZipTestSupport.springBootParentPom("3.2.5"))));

    assertThat(result.inspection().supported()).isTrue();
  }

  @Test
  @DisplayName("Removes the workspace when extraction fails (e.g. corrupt zip)")
  void removesWorkspaceWhenExtractionFails() throws Exception {
    MockMultipartFile corrupt =
        new MockMultipartFile(
            "file", "broken.zip", "application/zip", "not a zip".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> service.upload(corrupt)).isInstanceOf(UploadException.class);
    try (var children = Files.list(workspaceDir)) {
      assertThat(children).isEmpty();
    }
  }
}
