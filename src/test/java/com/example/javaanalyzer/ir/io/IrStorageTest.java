package com.example.javaanalyzer.ir.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.javaanalyzer.analysis.AnalysisTestSupport;
import com.example.javaanalyzer.analysis.ProjectAnalyzer;
import com.example.javaanalyzer.analysis.plugin.springboot.SpringBootPlugin;
import com.example.javaanalyzer.ir.ProjectModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IrStorageTest {

  @TempDir Path tempDir;

  private ProjectModel analyze(String... sources) {
    Path projectRoot = tempDir.resolve("project");
    for (String source : sources) {
      AnalysisTestSupport.writeSource(projectRoot, source);
    }
    return new ProjectAnalyzer(List.of(new SpringBootPlugin())).analyze(projectRoot);
  }

  private ProjectModel sampleModel() {
    return analyze(
        """
        package com.example.web;
        import com.example.domain.Order;
        import org.springframework.web.bind.annotation.GetMapping;
        import org.springframework.web.bind.annotation.RestController;
        /** 注文API。 */
        @RestController
        public class OrderController {
          private final OrderService service;
          public OrderController(OrderService service) { this.service = service; }
          @GetMapping("/orders") public Order find() { return null; }
        }
        """,
        """
        package com.example.web;
        import org.springframework.stereotype.Service;
        @Service public class OrderService {}
        """,
        """
        package com.example.domain;
        import jakarta.persistence.Column;
        import jakarta.persistence.Entity;
        import jakarta.persistence.Id;
        @Entity
        public class Order {
          @Id private Long id;
          @Column(unique = true, name = "code", length = 8, nullable = false, updatable = false)
          private String code;
        }
        """);
  }

  private Path irDir() {
    return tempDir.resolve("ir");
  }

  @Test
  @DisplayName("Writes project.json and one file per package")
  void writesIndexAndPackageFiles() {
    IrWriter.write(sampleModel(), irDir());

    assertThat(irDir().resolve("project.json")).isRegularFile();
    assertThat(irDir().resolve("packages/com.example.web.json")).isRegularFile();
    assertThat(irDir().resolve("packages/com.example.domain.json")).isRegularFile();
  }

  @Test
  @DisplayName("Reading what was written restores an equal ProjectModel")
  void roundTripRestoresEqualModel() {
    ProjectModel model = sampleModel();

    IrWriter.write(model, irDir());

    assertThat(IrReader.read(irDir())).isEqualTo(model);
  }

  @Test
  @DisplayName("project.json holds the index, dependencies and issues, but no type details")
  void indexContainsSummaryOnly() throws IOException {
    IrWriter.write(sampleModel(), irDir());

    String index = Files.readString(irDir().resolve("project.json"));

    assertThat(index)
        .contains("\"schemaVersion\"", "\"rootPackage\"", "\"dependencies\"", "\"issues\"")
        .contains("\"file\" : \"packages/com.example.web.json\"")
        .doesNotContain("\"javadoc\"");
  }

  @Test
  @DisplayName("Package files hold that package's types and components only")
  void packageFileContainsOnlyItsOwnContent() throws IOException {
    IrWriter.write(sampleModel(), irDir());

    String web = Files.readString(irDir().resolve("packages/com.example.web.json"));
    String domain = Files.readString(irDir().resolve("packages/com.example.domain.json"));

    assertThat(web).contains("OrderController", "/orders").doesNotContain("\"entities\" : [ {");
    assertThat(domain).contains("\"entities\" : [ {").doesNotContain("OrderController");
  }

  @Test
  @DisplayName("JSON is indented, keeps Japanese text readable and omits null values")
  void jsonIsHumanReadable() throws IOException {
    IrWriter.write(sampleModel(), irDir());

    String web = Files.readString(irDir().resolve("packages/com.example.web.json"));

    assertThat(web).contains("\n  \"").contains("注文API。").doesNotContain("null");
  }

  @Test
  @DisplayName("Annotation attributes are written in sorted order so output can be diffed")
  void mapEntriesAreSorted() throws IOException {
    IrWriter.write(sampleModel(), irDir());

    String domain = Files.readString(irDir().resolve("packages/com.example.domain.json"));

    assertThat(domain)
        .containsSubsequence(
            "\"length\" :", "\"name\" :", "\"nullable\" :", "\"unique\" :", "\"updatable\" :");
  }

  @Test
  @DisplayName("Types in the default package go to _default.json and survive a round trip")
  void defaultPackageRoundTrip() {
    ProjectModel model = analyze("public class NoPackage {}");

    IrWriter.write(model, irDir());

    assertThat(irDir().resolve("packages/_default.json")).isRegularFile();
    assertThat(IrReader.read(irDir())).isEqualTo(model);
  }

  @Test
  @DisplayName("Package names differing only by case get separate files on any file system")
  void caseInsensitiveCollisionIsAvoided() throws IOException {
    ProjectModel model =
        analyze("package com.Foo; public class Upper {}", "package com.foo; public class Lower {}");

    IrWriter.write(model, irDir());

    try (var files = Files.list(irDir().resolve("packages"))) {
      assertThat(files.toList()).hasSize(2);
    }
    assertThat(IrReader.read(irDir())).isEqualTo(model);
  }

  @Test
  @DisplayName("A project without sources writes an empty index and reads back")
  void emptyProjectRoundTrip() {
    ProjectModel model = analyze();

    IrWriter.write(model, irDir());

    assertThat(IrReader.read(irDir())).isEqualTo(model);
  }

  @Test
  @DisplayName("Unknown extra JSON fields are ignored when reading")
  void ignoresUnknownFields() throws IOException {
    ProjectModel model = sampleModel();
    IrWriter.write(model, irDir());
    Path index = irDir().resolve("project.json");
    Files.writeString(index, Files.readString(index).replaceFirst("\\{", "{ \"futureField\" : 1,"));

    assertThat(IrReader.read(irDir())).isEqualTo(model);
  }

  @Test
  @DisplayName("An unsupported schemaVersion is rejected with a message naming the version")
  void rejectsUnsupportedSchemaVersion() throws IOException {
    IrWriter.write(sampleModel(), irDir());
    Path index = irDir().resolve("project.json");
    Files.writeString(
        index,
        Files.readString(index)
            .replaceFirst("\"schemaVersion\" : \\d+", "\"schemaVersion\" : 999"));

    assertThatThrownBy(() -> IrReader.read(irDir()))
        .isInstanceOf(IrStorageException.class)
        .hasMessageContaining("999");
  }

  @Test
  @DisplayName(
      "IR of an older schema whose fields no longer match is reported as a version mismatch")
  void olderSchemaWithDifferentFieldsIsReportedAsVersionMismatch() throws IOException {
    Files.createDirectories(irDir());
    // Shape of schema version 1 before No.20-22 added unmatchedMappers and views
    Files.writeString(
        irDir().resolve("project.json"),
        """
        { "schemaVersion" : 1, "rootPackage" : "com.example",
          "packages" : [], "dependencies" : [], "issues" : [] }
        """);

    assertThatThrownBy(() -> IrReader.read(irDir()))
        .isInstanceOf(IrSchemaVersionException.class)
        .hasMessageContaining("1");
  }

  @Test
  @DisplayName("The current schema version is 3 (raised for the IR changes of No.17)")
  void currentSchemaVersionIsThree() throws IOException {
    IrWriter.write(sampleModel(), irDir());

    assertThat(Files.readString(irDir().resolve("project.json"))).contains("\"schemaVersion\" : 3");
  }

  @Test
  @DisplayName("Missing project.json is reported as IrStorageException")
  void missingIndexIsReported() {
    assertThatThrownBy(() -> IrReader.read(tempDir.resolve("does-not-exist")))
        .isInstanceOf(IrStorageException.class);
  }

  @Test
  @DisplayName("Malformed JSON is reported as IrStorageException")
  void malformedJsonIsReported() throws IOException {
    Files.createDirectories(irDir());
    Files.writeString(irDir().resolve("project.json"), "{ not json");

    assertThatThrownBy(() -> IrReader.read(irDir())).isInstanceOf(IrStorageException.class);
  }

  @Test
  @DisplayName("A package file listed in the index but missing is reported")
  void missingPackageFileIsReported() throws IOException {
    IrWriter.write(sampleModel(), irDir());
    Files.delete(irDir().resolve("packages/com.example.web.json"));

    assertThatThrownBy(() -> IrReader.read(irDir())).isInstanceOf(IrStorageException.class);
  }
}
