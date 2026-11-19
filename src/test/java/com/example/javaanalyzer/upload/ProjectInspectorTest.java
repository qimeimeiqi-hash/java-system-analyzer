package com.example.javaanalyzer.upload;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.upload.InspectionIssue.Severity;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectInspectorTest {

  @TempDir Path sourceDir;

  private final ProjectInspector inspector = new ProjectInspector();

  private void write(String relativePath, String content) throws IOException {
    Path file = sourceDir.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private void writeJavaSource(String projectPrefix) throws IOException {
    write(
        projectPrefix + "src/main/java/com/example/DemoApplication.java",
        "class DemoApplication {}");
  }

  private static boolean hasIssue(InspectionResult result, Severity severity, String fragment) {
    return result.issues().stream()
        .anyMatch(issue -> issue.severity() == severity && issue.message().contains(fragment));
  }

  @Test
  @DisplayName("Supported: pom at the root with spring-boot-starter-parent; version is read")
  void supportsSpringBootParentAtRoot() throws Exception {
    write("pom.xml", ZipTestSupport.springBootParentPom("3.2.5"));
    writeJavaSource("");

    InspectionResult result = inspector.inspect(sourceDir);

    assertThat(result.supported()).isTrue();
    assertThat(result.springBootVersion()).isEqualTo("3.2.5");
    assertThat(result.pomPath()).isEqualTo("pom.xml");
    assertThat(result.projectRoot()).isEqualTo(sourceDir.toAbsolutePath().normalize());
    assertThat(result.issues()).isEmpty();
  }

  @Test
  @DisplayName("Supported: project wrapped in a single top-level folder (e.g. GitHub download)")
  void supportsSingleTopLevelFolder() throws Exception {
    write("demo-main/pom.xml", ZipTestSupport.springBootParentPom("2.7.18"));
    writeJavaSource("demo-main/");

    InspectionResult result = inspector.inspect(sourceDir);

    assertThat(result.supported()).isTrue();
    assertThat(result.pomPath()).isEqualTo("demo-main/pom.xml");
    assertThat(result.projectRoot())
        .isEqualTo(sourceDir.resolve("demo-main").toAbsolutePath().normalize());
    assertThat(result.springBootVersion()).isEqualTo("2.7.18");
  }

  @Test
  @DisplayName(
      "Supported: macOS __MACOSX folder is ignored when finding the single top-level folder")
  void ignoresMacOsMetadataFolder() throws Exception {
    write("demo/pom.xml", ZipTestSupport.springBootParentPom("3.3.0"));
    writeJavaSource("demo/");
    write("__MACOSX/demo/._pom.xml", "junk");

    InspectionResult result = inspector.inspect(sourceDir);

    assertThat(result.supported()).isTrue();
    assertThat(result.pomPath()).isEqualTo("demo/pom.xml");
  }

  @Test
  @DisplayName(
      "Supported: Spring Boot detected via org.springframework.boot dependency without boot parent")
  void supportsSpringBootDependencyWithoutParent() throws Exception {
    write(
        "pom.xml",
        """
        <project>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
          <dependencies>
            <dependency>
              <groupId>org.springframework.boot</groupId>
              <artifactId>spring-boot-starter-web</artifactId>
            </dependency>
          </dependencies>
        </project>
        """);
    writeJavaSource("");

    InspectionResult result = inspector.inspect(sourceDir);

    assertThat(result.supported()).isTrue();
    assertThat(result.springBootVersion()).isNull();
  }

  @Test
  @DisplayName("Supported: spring-boot-dependencies BOM import with version from a property")
  void supportsBomImportWithPropertyVersion() throws Exception {
    write(
        "pom.xml",
        """
        <project>
          <properties>
            <spring-boot.version>3.1.4</spring-boot.version>
          </properties>
          <dependencyManagement>
            <dependencies>
              <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
              </dependency>
            </dependencies>
          </dependencyManagement>
        </project>
        """);
    writeJavaSource("");

    InspectionResult result = inspector.inspect(sourceDir);

    assertThat(result.supported()).isTrue();
    assertThat(result.springBootVersion()).isEqualTo("3.1.4");
  }

  @Test
  @DisplayName("Version is unknown (null) when the referenced property is not defined")
  void versionIsNullWhenPropertyUndefined() throws Exception {
    write("pom.xml", ZipTestSupport.springBootParentPom("${boot.version}"));
    writeJavaSource("");

    InspectionResult result = inspector.inspect(sourceDir);

    assertThat(result.supported()).isTrue();
    assertThat(result.springBootVersion()).isNull();
  }

  @Test
  @DisplayName("Unsupported: no pom.xml anywhere")
  void unsupportedWhenNoPom() throws Exception {
    write("README.md", "hello");

    InspectionResult result = inspector.inspect(sourceDir);

    assertThat(result.supported()).isFalse();
    assertThat(result.projectRoot()).isNull();
    assertThat(hasIssue(result, Severity.ERROR, "pom.xml")).isTrue();
  }

  @Test
  @DisplayName("Unsupported: pom.xml nested two levels deep is not searched")
  void unsupportedWhenPomTooDeep() throws Exception {
    write("outer/inner/pom.xml", ZipTestSupport.springBootParentPom("3.2.5"));

    InspectionResult result = inspector.inspect(sourceDir);

    assertThat(result.supported()).isFalse();
    assertThat(hasIssue(result, Severity.ERROR, "pom.xml")).isTrue();
  }

  @Test
  @DisplayName("Unsupported: Gradle project is reported as planned for V3")
  void unsupportedGradleProject() throws Exception {
    write("demo/build.gradle", "plugins { id 'org.springframework.boot' version '3.2.5' }");

    InspectionResult result = inspector.inspect(sourceDir);

    assertThat(result.supported()).isFalse();
    assertThat(hasIssue(result, Severity.ERROR, "Gradle")).isTrue();
  }

  @Test
  @DisplayName("Unsupported: multi-module pom with <modules>")
  void unsupportedMultiModule() throws Exception {
    write(
        "pom.xml",
        """
        <project>
          <parent>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-parent</artifactId>
            <version>3.2.5</version>
          </parent>
          <packaging>pom</packaging>
          <modules>
            <module>api</module>
            <module>core</module>
          </modules>
        </project>
        """);

    InspectionResult result = inspector.inspect(sourceDir);

    assertThat(result.supported()).isFalse();
    assertThat(hasIssue(result, Severity.ERROR, "マルチモジュール")).isTrue();
  }

  @Test
  @DisplayName("Unsupported: plain Maven project without any Spring Boot reference")
  void unsupportedNonSpringBootMaven() throws Exception {
    write(
        "pom.xml",
        """
        <project>
          <groupId>com.example</groupId>
          <artifactId>plain</artifactId>
          <dependencies>
            <dependency>
              <groupId>org.springframework</groupId>
              <artifactId>spring-webmvc</artifactId>
            </dependency>
          </dependencies>
        </project>
        """);
    writeJavaSource("");

    InspectionResult result = inspector.inspect(sourceDir);

    assertThat(result.supported()).isFalse();
    assertThat(hasIssue(result, Severity.ERROR, "Spring Boot")).isTrue();
  }

  @Test
  @DisplayName("Unsupported: malformed pom.xml is reported instead of throwing")
  void unsupportedMalformedPom() throws Exception {
    write("pom.xml", "<project><parent></project>");

    InspectionResult result = inspector.inspect(sourceDir);

    assertThat(result.supported()).isFalse();
    assertThat(hasIssue(result, Severity.ERROR, "解析")).isTrue();
  }

  @Test
  @DisplayName("Rejects a pom with DOCTYPE so external entities are never resolved (XXE)")
  void rejectsDoctypeToPreventXxe() throws Exception {
    Path secret = Files.writeString(sourceDir.resolve("secret.txt"), "TOP-SECRET");
    write(
        "pom.xml",
        """
        <?xml version="1.0"?>
        <!DOCTYPE project [ <!ENTITY xxe SYSTEM "%s"> ]>
        <project>
          <parent>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-parent</artifactId>
            <version>&xxe;</version>
          </parent>
        </project>
        """
            .formatted(secret.toUri()));

    InspectionResult result = inspector.inspect(sourceDir);

    assertThat(result.supported()).isFalse();
    assertThat(result.springBootVersion()).isNull();
    assertThat(result.issues()).noneMatch(issue -> issue.message().contains("TOP-SECRET"));
  }

  private static String pomWithProperties(String properties) {
    return """
        <project>
          <properties>%s</properties>
          <parent>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-parent</artifactId>
            <version>3.2.5</version>
          </parent>
        </project>
        """
        .formatted(properties);
  }

  @Test
  @DisplayName("Source encoding defaults to UTF-8 when the pom does not declare one")
  void sourceEncodingDefaultsToUtf8() throws Exception {
    write("pom.xml", ZipTestSupport.springBootParentPom("3.2.5"));
    writeJavaSource("");

    InspectionResult result = inspector.inspect(sourceDir);

    assertThat(result.sourceEncoding()).isEqualTo(StandardCharsets.UTF_8);
    assertThat(result.issues()).isEmpty();
  }

  @Test
  @DisplayName("Source encoding is read from project.build.sourceEncoding")
  void readsDeclaredSourceEncoding() throws Exception {
    write(
        "pom.xml",
        pomWithProperties(
            "<project.build.sourceEncoding>Shift_JIS</project.build.sourceEncoding>"));
    writeJavaSource("");

    InspectionResult result = inspector.inspect(sourceDir);

    assertThat(result.sourceEncoding()).isEqualTo(Charset.forName("Shift_JIS"));
    assertThat(result.issues()).isEmpty();
  }

  @Test
  @DisplayName("Source encoding given as a property reference is resolved")
  void resolvesSourceEncodingReference() throws Exception {
    write(
        "pom.xml",
        pomWithProperties(
            "<enc>windows-31j</enc>"
                + "<project.build.sourceEncoding>${enc}</project.build.sourceEncoding>"));
    writeJavaSource("");

    InspectionResult result = inspector.inspect(sourceDir);

    assertThat(result.sourceEncoding()).isEqualTo(Charset.forName("windows-31j"));
  }

  @Test
  @DisplayName("An unknown source encoding falls back to UTF-8 with a warning naming it")
  void warnsOnUnknownSourceEncoding() throws Exception {
    write(
        "pom.xml",
        pomWithProperties("<project.build.sourceEncoding>NO-SUCH</project.build.sourceEncoding>"));
    writeJavaSource("");

    InspectionResult result = inspector.inspect(sourceDir);

    assertThat(result.supported()).isTrue();
    assertThat(result.sourceEncoding()).isEqualTo(StandardCharsets.UTF_8);
    assertThat(hasIssue(result, Severity.WARNING, "NO-SUCH")).isTrue();
  }

  @Test
  @DisplayName("An illegal charset name also falls back to UTF-8 instead of throwing")
  void warnsOnIllegalSourceEncodingName() throws Exception {
    write(
        "pom.xml",
        pomWithProperties(
            "<project.build.sourceEncoding>bad name!</project.build.sourceEncoding>"));
    writeJavaSource("");

    InspectionResult result = inspector.inspect(sourceDir);

    assertThat(result.sourceEncoding()).isEqualTo(StandardCharsets.UTF_8);
    assertThat(hasIssue(result, Severity.WARNING, "bad name!")).isTrue();
  }

  @Test
  @DisplayName("Warning only: Spring Boot project without Java sources under src/main/java")
  void warnsWhenNoJavaSources() throws Exception {
    write("pom.xml", ZipTestSupport.springBootParentPom("3.2.5"));

    InspectionResult result = inspector.inspect(sourceDir);

    assertThat(result.supported()).isTrue();
    assertThat(hasIssue(result, Severity.WARNING, "src/main/java")).isTrue();
  }
}
