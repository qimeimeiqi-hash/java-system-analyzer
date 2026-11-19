package com.example.javaanalyzer.analysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds an {@link AnalysisContext} from Java source strings, as the core would before plugins run.
 * Each source is written to {@code src/main/java/<package path>/<first type name>.java}.
 */
public final class AnalysisTestSupport {

  private static final Pattern PACKAGE =
      Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
  private static final Pattern FIRST_TYPE =
      Pattern.compile("\\b(?:class|interface|enum|record|@interface)\\s+(\\w+)");

  private AnalysisTestSupport() {}

  public static AnalysisContext contextOf(Path projectRoot, String... sources) {
    for (String source : sources) {
      writeSource(projectRoot, source);
    }
    return new ProjectAnalyzer(List.of()).buildContext(projectRoot, StandardCharsets.UTF_8);
  }

  public static Path writeSource(Path projectRoot, String source) {
    Matcher packageMatcher = PACKAGE.matcher(source);
    String packagePath = packageMatcher.find() ? packageMatcher.group(1).replace('.', '/') : "";
    Matcher typeMatcher = FIRST_TYPE.matcher(source);
    if (!typeMatcher.find()) {
      throw new IllegalArgumentException("No type declaration in source:\n" + source);
    }
    Path file =
        projectRoot
            .resolve("src/main/java")
            .resolve(packagePath)
            .resolve(typeMatcher.group(1) + ".java");
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, source);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return file;
  }
}
