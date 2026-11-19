package com.example.javaanalyzer.analysis.plugin.view;

import com.example.javaanalyzer.ir.AnalysisIssue;
import com.example.javaanalyzer.ir.IssueKind;
import com.example.javaanalyzer.ir.IssueSeverity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * The Spring Boot settings that tell which template a view name renders, read as written from
 * {@code src/main/resources/application.yml}/{@code .yaml}/{@code .properties} (a later file
 * overrides an earlier one, as in Spring Boot). Profile-specific files and documents, other config
 * locations, environment variables and {@code ViewResolver} beans are not read; a value with a
 * placeholder ({@code ${...}}) is not used.
 *
 * <p>YAML is read by SnakeYAML (a dependency of Spring Boot) with {@link SafeConstructor}, which
 * creates plain maps, lists and scalars only.
 */
final class ViewSettings {

  static final String THYMELEAF_PREFIX = "spring.thymeleaf.prefix";
  static final String THYMELEAF_SUFFIX = "spring.thymeleaf.suffix";
  static final String MVC_PREFIX = "spring.mvc.view.prefix";
  static final String MVC_SUFFIX = "spring.mvc.view.suffix";

  private static final String RESOURCES = "src/main/resources/";
  private static final List<String> FILES =
      List.of("application.yml", "application.yaml", "application.properties");
  private static final List<String> PROFILE_KEYS =
      List.of("spring.config.activate.on-profile", "spring.profiles");
  private static final List<String> KEYS =
      List.of(THYMELEAF_PREFIX, THYMELEAF_SUFFIX, MVC_PREFIX, MVC_SUFFIX);
  private static final String PLACEHOLDER = "${";

  private final Map<String, String> values = new HashMap<>();
  private final Map<String, String> sources = new HashMap<>();

  private ViewSettings() {}

  static ViewSettings read(Path projectRoot, List<AnalysisIssue> issues) {
    ViewSettings settings = new ViewSettings();
    for (String fileName : FILES) {
      Path file = projectRoot.resolve(RESOURCES + fileName);
      if (!Files.isRegularFile(file)) {
        continue;
      }
      String relativePath = RESOURCES + fileName;
      try {
        Map<String, String> read =
            fileName.endsWith(".properties") ? readProperties(file) : readYaml(file);
        for (String key : KEYS) {
          if (read.containsKey(key)) {
            settings.values.put(key, read.get(key));
            settings.sources.put(key, relativePath);
          }
        }
      } catch (IOException | YAMLException | IllegalArgumentException e) {
        issues.add(
            new AnalysisIssue(
                IssueKind.VIEW_SETTING_UNRESOLVED,
                IssueSeverity.WARNING,
                relativePath,
                null,
                "設定ファイルを読み込めないため、画面の設定（spring.thymeleaf.*、spring.mvc.view.*）を" + "確認できません。"));
      }
    }
    return settings;
  }

  /** The value as written, or null when not set. */
  String value(String key) {
    return values.get(key);
  }

  /** The file the value was read from, or null when not set. */
  String source(String key) {
    return sources.get(key);
  }

  static boolean hasPlaceholder(String value) {
    return value != null && value.contains(PLACEHOLDER);
  }

  private static Map<String, String> readProperties(Path file) throws IOException {
    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      properties.load(in);
    }
    Map<String, String> result = new HashMap<>();
    properties.stringPropertyNames().forEach(k -> result.put(k, properties.getProperty(k)));
    return result;
  }

  private static Map<String, String> readYaml(Path file) throws IOException {
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Map<String, String> result = new HashMap<>();
    for (Object document : yaml.loadAll(Files.readString(file, StandardCharsets.UTF_8))) {
      Map<String, String> flat = new HashMap<>();
      flatten("", document, flat);
      if (PROFILE_KEYS.stream().noneMatch(flat::containsKey)) {
        result.putAll(flat);
      }
    }
    return result;
  }

  private static void flatten(String prefix, Object node, Map<String, String> result) {
    if (node instanceof Map<?, ?> map) {
      map.forEach(
          (key, value) ->
              flatten(prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key, value, result));
    } else if (node != null && !(node instanceof List<?>) && !prefix.isEmpty()) {
      result.put(prefix, String.valueOf(node));
    } else if (node instanceof List<?> && !prefix.isEmpty()) {
      result.put(prefix, "");
    }
  }
}
