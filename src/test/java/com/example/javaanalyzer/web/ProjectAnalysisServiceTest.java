package com.example.javaanalyzer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.example.javaanalyzer.analysis.ProjectAnalyzer;
import com.example.javaanalyzer.analysis.plugin.springboot.SpringBootPlugin;
import com.example.javaanalyzer.ir.IssueKind;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.io.IrReader;
import com.example.javaanalyzer.upload.InspectionIssue;
import com.example.javaanalyzer.upload.InspectionResult;
import com.example.javaanalyzer.upload.ProjectStorage;
import com.example.javaanalyzer.upload.UploadProperties;
import com.example.javaanalyzer.upload.UploadResult;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class ProjectAnalysisServiceTest {

  private static final String PROJECT_ID = "3f2b8c1e-7a4d-4f60-9b21-0c5de8a1f7aa";
  private static final Charset MS932 = Charset.forName("MS932");

  /** Valid in MS932 but not in UTF-8, so decoding it as UTF-8 would raise a fallback issue. */
  private static final String JAPANESE_SOURCE =
      "package com.example;\n/** 注文サービス */\npublic class OrderService {}\n";

  @TempDir Path tempDir;

  private ProjectStorage storage;
  private ProjectAnalysisService service;

  @BeforeEach
  void setUp() {
    UploadProperties properties =
        new UploadProperties(tempDir.resolve("workspace").toString(), DataSize.ofMegabytes(1), 100);
    storage = new ProjectStorage(properties);
    service =
        new ProjectAnalysisService(new ProjectAnalyzer(List.of(new SpringBootPlugin())), storage);
  }

  private UploadResult uploadedProject(Charset encoding) throws IOException {
    Path projectRoot = storage.sourceDir(PROJECT_ID).resolve("demo");
    Path source = projectRoot.resolve("src/main/java/com/example/OrderService.java");
    Files.createDirectories(source.getParent());
    Files.write(source, JAPANESE_SOURCE.getBytes(encoding));
    return new UploadResult(
        PROJECT_ID,
        new InspectionResult(projectRoot, "demo/pom.xml", "3.2.5", List.of(), encoding));
  }

  @Test
  @DisplayName("Analyzes the uploaded project and saves readable IR under the project's ir folder")
  void analyzesAndSavesIr() throws Exception {
    ProjectModel model = service.analyze(uploadedProject(StandardCharsets.UTF_8));

    assertThat(model.types())
        .extracting("qualifiedName")
        .containsExactly("com.example.OrderService");
    assertThat(IrReader.read(storage.irDir(PROJECT_ID))).isEqualTo(model);
  }

  @Test
  @DisplayName("Reads sources with the encoding declared by the project, without a fallback issue")
  void usesDeclaredSourceEncoding() throws Exception {
    ProjectModel model = service.analyze(uploadedProject(MS932));

    assertThat(model.issues()).noneMatch(issue -> issue.kind() == IssueKind.ENCODING_FALLBACK);
    assertThat(model.types().get(0).javadoc()).contains("注文サービス");
  }

  @Test
  @DisplayName("Finds a saved IR file by project id and relative path")
  void findsSavedIrFile() throws Exception {
    service.analyze(uploadedProject(StandardCharsets.UTF_8));

    assertThat(service.irFile(PROJECT_ID, "project.json"))
        .hasValue(storage.irDir(PROJECT_ID).resolve("project.json"));
    assertThat(service.irFile(PROJECT_ID, "packages/com.example.json")).isPresent();
  }

  @Test
  @DisplayName("Rejects project ids that are not UUIDs, even if such a folder exists")
  void rejectsNonUuidProjectId() throws Exception {
    Files.createDirectories(storage.irDir("not-a-uuid"));
    Files.writeString(storage.irDir("not-a-uuid").resolve("project.json"), "{}");

    assertThat(service.irFile("not-a-uuid", "project.json")).isEmpty();
    assertThat(service.irFile("..", "project.json")).isEmpty();
  }

  @Test
  @DisplayName("Rejects paths that leave the IR folder, and files that do not exist")
  void rejectsTraversalAndMissingFiles() throws Exception {
    service.analyze(uploadedProject(StandardCharsets.UTF_8));
    Path outside = storage.irDir(PROJECT_ID).resolveSibling("secret.json");
    Files.writeString(outside, "{}");

    assertThat(service.irFile(PROJECT_ID, "../secret.json")).isEmpty();
    assertThat(service.irFile(PROJECT_ID, "packages/../../secret.json")).isEmpty();
    assertThat(service.irFile(PROJECT_ID, outside.toAbsolutePath().toString())).isEmpty();
    assertThat(service.irFile(PROJECT_ID, "packages/missing.json")).isEmpty();
    assertThat(service.irFile(PROJECT_ID, "packages")).as("a directory is not a file").isEmpty();
  }

  @Test
  @DisplayName("A path that the file system cannot represent is treated as not found")
  void invalidPathIsNotFound() throws Exception {
    service.analyze(uploadedProject(StandardCharsets.UTF_8));

    assertThat(service.irFile(PROJECT_ID, "packages/bad\0name.json")).isEmpty();
  }

  @Test
  @DisplayName("A supported project is saved as SUCCEEDED and reopened with its IR and inspection")
  void savesAndReloadsSucceededProject() throws Exception {
    AnalysisStatus status = service.analyzeAndSave(uploadedProject(MS932));

    assertThat(status).isEqualTo(AnalysisStatus.SUCCEEDED);
    ProjectResult result = service.load(PROJECT_ID).orElseThrow();
    assertThat(result.saved().status()).isEqualTo(AnalysisStatus.SUCCEEDED);
    assertThat(result.saved().pomPath()).isEqualTo("demo/pom.xml");
    assertThat(result.saved().springBootVersion()).isEqualTo("3.2.5");
    assertThat(result.saved().sourceEncoding()).isEqualTo(MS932.name());
    assertThat(result.irOutdated()).isFalse();
    assertThat(result.model()).isEqualTo(IrReader.read(storage.irDir(PROJECT_ID)));
  }

  @Test
  @DisplayName(
      "A syntax error in a source file is a FR-14 finding: the analysis still SUCCEEDS with an issue")
  void syntaxErrorInSourceIsAnIssueNotAFailure() throws Exception {
    UploadResult upload = uploadedProject(StandardCharsets.UTF_8);
    Path broken =
        upload.inspection().projectRoot().resolve("src/main/java/com/example/Broken.java");
    Files.writeString(
        broken,
        """
        package com.example;
        public class Broken {
          void x( {
        }
        """);

    AnalysisStatus status = service.analyzeAndSave(upload);

    assertThat(status).isEqualTo(AnalysisStatus.SUCCEEDED);
    assertThat(service.load(PROJECT_ID).orElseThrow().model().issues())
        .anyMatch(issue -> issue.kind() == IssueKind.SYNTAX_ERROR);
  }

  @Test
  @DisplayName("An unsupported project is saved with its reasons and is never analyzed")
  void savesUnsupportedProjectWithoutAnalysis() {
    InspectionResult inspection =
        new InspectionResult(null, null, null, List.of(InspectionIssue.error("pom.xml が見つかりません。")));

    AnalysisStatus status = service.analyzeAndSave(new UploadResult(PROJECT_ID, inspection));

    assertThat(status).isEqualTo(AnalysisStatus.UNSUPPORTED);
    assertThat(Files.exists(storage.irDir(PROJECT_ID))).isFalse();
    ProjectResult result = service.load(PROJECT_ID).orElseThrow();
    assertThat(result.model()).isNull();
    assertThat(result.saved().pomPath()).isNull();
    assertThat(result.saved().inspectionIssues())
        .containsExactly(InspectionIssue.error("pom.xml が見つかりません。"));
  }

  @Test
  @DisplayName("An analysis failure is saved as FAILED instead of propagating the exception")
  void savesFailedAnalysis() throws Exception {
    ProjectAnalyzer failingAnalyzer = mock(ProjectAnalyzer.class);
    given(failingAnalyzer.analyze(any(), any())).willThrow(new IllegalStateException("boom"));
    ProjectAnalysisService failingService = new ProjectAnalysisService(failingAnalyzer, storage);

    AnalysisStatus status = failingService.analyzeAndSave(uploadedProject(StandardCharsets.UTF_8));

    assertThat(status).isEqualTo(AnalysisStatus.FAILED);
    ProjectResult result = failingService.load(PROJECT_ID).orElseThrow();
    assertThat(result.saved().status()).isEqualTo(AnalysisStatus.FAILED);
    assertThat(result.saved().pomPath()).isEqualTo("demo/pom.xml");
    assertThat(result.model()).isNull();
  }

  @Test
  @DisplayName("A stack overflow during analysis is saved as FAILED, so the result page exists")
  void savesStackOverflowAsFailed() throws Exception {
    ProjectAnalyzer failingAnalyzer = mock(ProjectAnalyzer.class);
    given(failingAnalyzer.analyze(any(), any())).willThrow(new StackOverflowError());
    ProjectAnalysisService failingService = new ProjectAnalysisService(failingAnalyzer, storage);

    AnalysisStatus status = failingService.analyzeAndSave(uploadedProject(StandardCharsets.UTF_8));

    assertThat(status).isEqualTo(AnalysisStatus.FAILED);
    assertThat(failingService.load(PROJECT_ID).orElseThrow().saved().status())
        .isEqualTo(AnalysisStatus.FAILED);
  }

  @Test
  @DisplayName("IR saved with an older schema version is reported as outdated, not as an error")
  void reportsOutdatedIr() throws Exception {
    service.analyzeAndSave(uploadedProject(StandardCharsets.UTF_8));
    Path index = storage.irDir(PROJECT_ID).resolve("project.json");
    Files.writeString(
        index,
        Files.readString(index).replaceFirst("\"schemaVersion\" : \\d+", "\"schemaVersion\" : 1"));

    ProjectResult result = service.load(PROJECT_ID).orElseThrow();

    assertThat(result.irOutdated()).isTrue();
    assertThat(result.model()).isNull();
  }

  @Test
  @DisplayName("Unknown and malformed project ids are not found")
  void unknownProjectIsNotFound() {
    assertThat(service.load(PROJECT_ID)).isEmpty();
    assertThat(service.load("../" + PROJECT_ID)).isEmpty();
  }
}
