package com.example.javaanalyzer.analysis.plugin.view;

import com.example.javaanalyzer.analysis.AnalysisContext;
import com.example.javaanalyzer.analysis.plugin.AnalyzerPlugin;
import com.example.javaanalyzer.ir.AnalysisIssue;
import com.example.javaanalyzer.ir.IssueKind;
import com.example.javaanalyzer.ir.IssueSeverity;
import com.example.javaanalyzer.ir.TemplateIncludeModel;
import com.example.javaanalyzer.ir.TemplateKind;
import com.example.javaanalyzer.ir.ViewTemplateModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Screen (view) layer analyzer for JSP and Thymeleaf (FR-17): lists the templates, the view name
 * each one renders, and the links, form targets and parts written in them. Which endpoint a link
 * reaches and which controller method returns a view name are linked afterwards from the IR alone.
 *
 * <p>Templates are found where Spring Boot looks for them:
 *
 * <ul>
 *   <li>Thymeleaf: files ending with {@code spring.thymeleaf.suffix} (default {@code .html}) under
 *       {@code spring.thymeleaf.prefix} (default {@code classpath:/templates/}), only when {@code
 *       pom.xml} declares a Thymeleaf artifact, because other template engines also use {@code
 *       templates/}. The view name is the path between prefix and suffix.
 *   <li>JSP: {@code .jsp}/{@code .jspx}/{@code .jspf} files under {@code src/main/webapp} and
 *       {@code src/main/resources/META-INF/resources}. JSP has no default prefix in Spring Boot, so
 *       the view name is known only when {@code spring.mvc.view.prefix} is written; otherwise it is
 *       null and a warning is reported instead of guessing.
 * </ul>
 *
 * <p>Template files are read as UTF-8; bytes that are not UTF-8 do not stop the analysis.
 *
 * <p>Known limitations: see {@link ViewSettings} for the settings read and {@link TemplateScanner}
 * for what is read from a template; a {@code ViewResolver} defined as a bean is not read.
 */
@Component
public class ViewTemplatePlugin implements AnalyzerPlugin {

  private static final String RESOURCES = "src/main/resources/";
  private static final String CLASSPATH = "classpath:";
  private static final String DEFAULT_THYMELEAF_PREFIX = "classpath:/templates/";
  private static final String DEFAULT_THYMELEAF_SUFFIX = ".html";
  private static final List<String> JSP_ROOTS =
      List.of("src/main/webapp", "src/main/resources/META-INF/resources");
  private static final Set<String> JSP_EXTENSIONS = Set.of(".jsp", ".jspx", ".jspf");
  private static final Pattern THYMELEAF_ARTIFACT =
      Pattern.compile("<artifactId>\\s*[^<]*thymeleaf[^<]*</artifactId>");

  /** A template file found on disk, before it is read. */
  private record Found(Path file, String path, TemplateKind kind, String viewName, String root) {}

  @Override
  public String name() {
    return "View (JSP / Thymeleaf)";
  }

  /** Always supported: templates can exist without any Java type referring to them. */
  @Override
  public boolean supports(AnalysisContext context) {
    return true;
  }

  @Override
  public void analyze(AnalysisContext context) {
    Path root = context.projectRoot();
    List<AnalysisIssue> issues = new ArrayList<>();
    ViewSettings settings = ViewSettings.read(root, issues);
    List<Found> found = new ArrayList<>();
    if (usesThymeleaf(root)) {
      found.addAll(thymeleafTemplates(root, settings, issues));
    }
    found.addAll(jspTemplates(root, settings, issues));
    Set<String> foundPaths = found.stream().map(Found::path).collect(Collectors.toSet());
    for (Found template : found) {
      TemplateScanner.Result scan = TemplateScanner.scan(read(template.file()), template.kind());
      List<TemplateIncludeModel> includes =
          scan.includes().stream()
              .map(
                  include ->
                      new TemplateIncludeModel(
                          include.attribute(),
                          include.value(),
                          include.templateName(),
                          includedPaths(template, include.templateName(), settings, foundPaths),
                          include.line()))
              .toList();
      context.addView(
          new ViewTemplateModel(
              template.path(), template.kind(), template.viewName(), scan.links(), includes));
    }
    issues.forEach(context::addIssue);
  }

  private static boolean usesThymeleaf(Path root) {
    Path pom = root.resolve("pom.xml");
    try {
      return Files.isRegularFile(pom)
          && THYMELEAF_ARTIFACT
              .matcher(new String(Files.readAllBytes(pom), StandardCharsets.UTF_8))
              .find();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<Found> thymeleafTemplates(
      Path root, ViewSettings settings, List<AnalysisIssue> issues) {
    String prefix = thymeleafPrefix(settings);
    String suffix = setting(settings, ViewSettings.THYMELEAF_SUFFIX, DEFAULT_THYMELEAF_SUFFIX);
    if (ViewSettings.hasPlaceholder(prefix) || ViewSettings.hasPlaceholder(suffix)) {
      issues.add(
          unresolved(
              settings,
              ViewSettings.THYMELEAF_PREFIX,
              "Thymeleafのテンプレートの場所（spring.thymeleaf.prefix/suffix）がプレースホルダで"
                  + "指定されているため、テンプレートを検出できません。"));
      return List.of();
    }
    if (!prefix.startsWith(CLASSPATH)) {
      issues.add(
          unresolved(
              settings,
              ViewSettings.THYMELEAF_PREFIX,
              "Thymeleafのテンプレートの場所がクラスパス外（" + prefix + "）のため、テンプレートを検出できません。"));
      return List.of();
    }
    String directory = RESOURCES + stripSlashes(prefix.substring(CLASSPATH.length()));
    List<Found> found = new ArrayList<>();
    for (Path file : files(root, directory)) {
      String relative = relativePath(root.resolve(directory), file);
      if (relative.endsWith(suffix) && relative.length() > suffix.length()) {
        String viewName = relative.substring(0, relative.length() - suffix.length());
        found.add(
            new Found(file, relativePath(root, file), TemplateKind.THYMELEAF, viewName, directory));
      }
    }
    return found;
  }

  private static String thymeleafPrefix(ViewSettings settings) {
    String prefix = setting(settings, ViewSettings.THYMELEAF_PREFIX, DEFAULT_THYMELEAF_PREFIX);
    return prefix.endsWith("/") ? prefix : prefix + "/";
  }

  private static List<Found> jspTemplates(
      Path root, ViewSettings settings, List<AnalysisIssue> issues) {
    String prefix = settings.value(ViewSettings.MVC_PREFIX);
    String suffix = setting(settings, ViewSettings.MVC_SUFFIX, "");
    boolean placeholder =
        ViewSettings.hasPlaceholder(prefix) || ViewSettings.hasPlaceholder(suffix);
    boolean knowsViewNames = prefix != null && !placeholder;
    List<Found> found = new ArrayList<>();
    for (String webRoot : JSP_ROOTS) {
      for (Path file : files(root, webRoot)) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (JSP_EXTENSIONS.stream().noneMatch(name::endsWith)) {
          continue;
        }
        String webPath = "/" + relativePath(root.resolve(webRoot), file);
        String viewName = knowsViewNames ? jspViewName(webPath, prefix, suffix) : null;
        found.add(new Found(file, relativePath(root, file), TemplateKind.JSP, viewName, webRoot));
      }
    }
    if (!found.isEmpty() && !knowsViewNames) {
      issues.add(
          unresolved(
              settings,
              ViewSettings.MVC_PREFIX,
              placeholder
                  ? "JSPのView名の設定（spring.mvc.view.prefix/suffix）がプレースホルダで指定されている"
                      + "ため、JSPとControllerを対応付けできません。"
                  : "application.properties/ymlにspring.mvc.view.prefixが無いため、JSPと"
                      + "Controllerを対応付けできません（ViewResolverのBeanで設定している場合は"
                      + "読み取りません）。"));
    }
    return found;
  }

  /** The view name rendered by a JSP, as {@code InternalResourceViewResolver} builds the path. */
  private static String jspViewName(String webPath, String prefix, String suffix) {
    String normalizedPrefix = prefix.startsWith("/") ? prefix : "/" + prefix;
    if (!webPath.startsWith(normalizedPrefix)
        || !webPath.endsWith(suffix)
        || webPath.length() <= normalizedPrefix.length() + suffix.length()) {
      return null;
    }
    return webPath.substring(normalizedPrefix.length(), webPath.length() - suffix.length());
  }

  private static List<String> includedPaths(
      Found template, String target, ViewSettings settings, Set<String> foundPaths) {
    if (target == null) {
      return List.of();
    }
    String candidate;
    if (template.kind() == TemplateKind.THYMELEAF) {
      String suffix = setting(settings, ViewSettings.THYMELEAF_SUFFIX, DEFAULT_THYMELEAF_SUFFIX);
      candidate = template.root() + "/" + target + suffix;
    } else if (target.startsWith("/")) {
      candidate = template.root() + target;
    } else {
      int slash = template.path().lastIndexOf('/');
      candidate = template.path().substring(0, slash + 1) + target;
    }
    String normalized = Path.of(candidate).normalize().toString().replace('\\', '/');
    if (foundPaths.contains(normalized)) {
      return List.of(normalized);
    }
    if (template.kind() == TemplateKind.JSP && target.startsWith("/")) {
      // An absolute path may point into the other web root.
      for (String webRoot : JSP_ROOTS) {
        String other = Path.of(webRoot + target).normalize().toString().replace('\\', '/');
        if (foundPaths.contains(other)) {
          return List.of(other);
        }
      }
    }
    return List.of();
  }

  private static String setting(ViewSettings settings, String key, String defaultValue) {
    String value = settings.value(key);
    return value == null ? defaultValue : value;
  }

  private static AnalysisIssue unresolved(ViewSettings settings, String key, String message) {
    return new AnalysisIssue(
        IssueKind.VIEW_SETTING_UNRESOLVED,
        IssueSeverity.WARNING,
        settings.source(key),
        null,
        message);
  }

  private static String stripSlashes(String path) {
    String result = path;
    while (result.startsWith("/")) {
      result = result.substring(1);
    }
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }

  /** Regular files under the directory, sorted by path; none when it does not exist. */
  private static List<Path> files(Path root, String directory) {
    Path dir = root.resolve(directory).normalize();
    if (!dir.startsWith(root.normalize()) || !Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      return paths.filter(Files::isRegularFile).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String read(Path file) {
    try {
      return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String relativePath(Path base, Path file) {
    return base.relativize(file).toString().replace('\\', '/');
  }
}
