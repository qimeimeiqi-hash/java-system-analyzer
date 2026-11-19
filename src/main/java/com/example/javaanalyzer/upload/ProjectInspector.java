package com.example.javaanalyzer.upload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Checks whether an extracted project is in V1 scope: a single-module Maven project using Spring
 * Boot. Only reads files; nothing in the project is executed.
 */
@Component
public class ProjectInspector {

  private static final String POM_FILE_NAME = "pom.xml";
  private static final String SPRING_BOOT_GROUP_ID = "org.springframework.boot";
  private static final String SPRING_BOOT_PARENT_ARTIFACT_ID = "spring-boot-starter-parent";
  private static final String SPRING_BOOT_BOM_ARTIFACT_ID = "spring-boot-dependencies";
  private static final String SOURCE_ENCODING_PROPERTY = "project.build.sourceEncoding";
  private static final String MAC_OS_METADATA_DIR = "__MACOSX";
  private static final Pattern PROPERTY_REFERENCE = Pattern.compile("^\\$\\{(.+)}$");

  public InspectionResult inspect(Path sourceDir) {
    Path root = sourceDir.toAbsolutePath().normalize();
    Optional<Path> projectRoot = locateProjectRoot(root);
    if (projectRoot.isEmpty()) {
      String message =
          containsGradleBuild(root)
              ? "Gradleプロジェクトは未対応です（V3で対応予定）。Mavenプロジェクトをアップロードしてください。"
              : "pom.xml が見つかりません。zipの直下、または直下の単一フォルダ内に pom.xml を配置してください。";
      return new InspectionResult(null, null, null, List.of(InspectionIssue.error(message)));
    }

    Path projectDir = projectRoot.get();
    String pomPath =
        root.relativize(projectDir.resolve(POM_FILE_NAME)).toString().replace('\\', '/');
    Element project;
    try {
      project = parsePom(projectDir.resolve(POM_FILE_NAME));
    } catch (IOException | SAXException e) {
      return new InspectionResult(
          projectDir,
          pomPath,
          null,
          List.of(InspectionIssue.error("pom.xml を解析できません（XMLとして不正な形式です）。")));
    }

    List<InspectionIssue> issues = new ArrayList<>();
    if (!childElements(child(project, "modules"), "module").isEmpty()) {
      issues.add(InspectionIssue.error("マルチモジュール構成（<modules>）は未対応です（V3で対応予定）。"));
    }
    SpringBootDetection detection = detectSpringBoot(project);
    if (!detection.detected()) {
      issues.add(
          InspectionIssue.error(
              "Spring Boot プロジェクトではありません（spring-boot-starter-parent、"
                  + "org.springframework.boot の依存関係、spring-boot-dependencies のいずれも見つかりません）。"));
    }
    if (!containsJavaSource(projectDir.resolve("src/main/java"))) {
      issues.add(InspectionIssue.warning("src/main/java 配下に Java ソースファイルが見つかりません。"));
    }
    Charset sourceEncoding = readSourceEncoding(project, issues);
    return new InspectionResult(projectDir, pomPath, detection.version(), issues, sourceEncoding);
  }

  /** The declared source encoding; UTF-8 when absent, with a warning when it cannot be used. */
  private static Charset readSourceEncoding(Element project, List<InspectionIssue> issues) {
    String declared = text(child(project, "properties"), SOURCE_ENCODING_PROPERTY);
    if (declared == null) {
      return StandardCharsets.UTF_8;
    }
    String name = resolvePropertyReference(project, declared);
    try {
      if (name != null && Charset.isSupported(name)) {
        return Charset.forName(name);
      }
    } catch (IllegalCharsetNameException e) {
      // Reported below in the same way as an unsupported name.
    }
    issues.add(
        InspectionIssue.warning(
            SOURCE_ENCODING_PROPERTY + "（" + declared + "）を使用できないため、UTF-8 として読み込みます。"));
    return StandardCharsets.UTF_8;
  }

  /** The archive root, or its only top-level folder (ignoring macOS metadata), holding pom.xml. */
  private static Optional<Path> locateProjectRoot(Path root) {
    if (Files.isRegularFile(root.resolve(POM_FILE_NAME))) {
      return Optional.of(root);
    }
    return singleTopLevelDirectory(root)
        .filter(dir -> Files.isRegularFile(dir.resolve(POM_FILE_NAME)));
  }

  private static Optional<Path> singleTopLevelDirectory(Path root) {
    try (Stream<Path> children = Files.list(root)) {
      List<Path> candidates =
          children
              .filter(child -> !child.getFileName().toString().equals(MAC_OS_METADATA_DIR))
              .toList();
      if (candidates.size() == 1 && Files.isDirectory(candidates.get(0))) {
        return Optional.of(candidates.get(0));
      }
      return Optional.empty();
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  private static boolean containsGradleBuild(Path root) {
    List<Path> candidates = new ArrayList<>(List.of(root));
    singleTopLevelDirectory(root).ifPresent(candidates::add);
    return candidates.stream()
        .anyMatch(
            dir ->
                Files.isRegularFile(dir.resolve("build.gradle"))
                    || Files.isRegularFile(dir.resolve("build.gradle.kts")));
  }

  private static boolean containsJavaSource(Path javaSourceDir) {
    if (!Files.isDirectory(javaSourceDir)) {
      return false;
    }
    try (Stream<Path> files = Files.walk(javaSourceDir)) {
      return files.anyMatch(
          file -> Files.isRegularFile(file) && file.getFileName().toString().endsWith(".java"));
    } catch (IOException e) {
      return false;
    }
  }

  /** Parses pom.xml with DOCTYPE disallowed so that external entities are never resolved (XXE). */
  private static Element parsePom(Path pomFile) throws IOException, SAXException {
    try (InputStream in = Files.newInputStream(pomFile)) {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      Document document = builder.parse(in);
      return document.getDocumentElement();
    } catch (ParserConfigurationException e) {
      throw new IllegalStateException(
          "XML parser does not support the required security features", e);
    }
  }

  private record SpringBootDetection(boolean detected, String version) {}

  private static SpringBootDetection detectSpringBoot(Element project) {
    Element parent = child(project, "parent");
    if (isSpringBootArtifact(parent, SPRING_BOOT_PARENT_ARTIFACT_ID)) {
      return new SpringBootDetection(
          true, resolvePropertyReference(project, text(parent, "version")));
    }
    for (Element dependency :
        childElements(
            child(child(project, "dependencyManagement"), "dependencies"), "dependency")) {
      if (isSpringBootArtifact(dependency, SPRING_BOOT_BOM_ARTIFACT_ID)) {
        return new SpringBootDetection(
            true, resolvePropertyReference(project, text(dependency, "version")));
      }
    }
    for (Element dependency : childElements(child(project, "dependencies"), "dependency")) {
      if (SPRING_BOOT_GROUP_ID.equals(text(dependency, "groupId"))) {
        return new SpringBootDetection(
            true, resolvePropertyReference(project, text(dependency, "version")));
      }
    }
    return new SpringBootDetection(false, null);
  }

  private static boolean isSpringBootArtifact(Element element, String artifactId) {
    return element != null
        && SPRING_BOOT_GROUP_ID.equals(text(element, "groupId"))
        && artifactId.equals(text(element, "artifactId"));
  }

  /** Resolves a {@code ${name}} reference from the pom's own properties; null if unresolvable. */
  private static String resolvePropertyReference(Element project, String version) {
    if (version == null) {
      return null;
    }
    Matcher reference = PROPERTY_REFERENCE.matcher(version);
    if (!reference.matches()) {
      return version;
    }
    String resolved = text(child(project, "properties"), reference.group(1));
    return resolved == null || resolved.contains("${") ? null : resolved;
  }

  private static Element child(Element parent, String name) {
    List<Element> children = childElements(parent, name);
    return children.isEmpty() ? null : children.get(0);
  }

  private static List<Element> childElements(Element parent, String name) {
    List<Element> result = new ArrayList<>();
    if (parent == null) {
      return result;
    }
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element && name.equals(localName(element))) {
        result.add(element);
      }
    }
    return result;
  }

  private static String localName(Element element) {
    String name = element.getTagName();
    int colon = name.indexOf(':');
    return colon < 0 ? name : name.substring(colon + 1);
  }

  private static String text(Element parent, String name) {
    Element element = child(parent, name);
    if (element == null) {
      return null;
    }
    String value = element.getTextContent().trim();
    return value.isEmpty() ? null : value;
  }
}
