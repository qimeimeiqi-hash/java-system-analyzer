package com.example.javaanalyzer.analysis;

import com.example.javaanalyzer.ir.AnalysisIssue;
import com.example.javaanalyzer.ir.IssueKind;
import com.example.javaanalyzer.ir.IssueSeverity;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Collects and decodes production sources under {@code src/main/java} (tests are excluded). */
final class SourceFileReader {

  static final String SOURCE_ROOT = "src/main/java";

  /** Legacy Japanese projects are often encoded in MS932 regardless of the declared encoding. */
  private static final Charset FALLBACK_CHARSET = Charset.forName("MS932");

  /**
   * @param relativePath path relative to the project root, with forward slashes
   * @param pathPackage package implied by the directory under {@code src/main/java}
   * @param pathTypeName qualified type name implied by the path and file name
   */
  record SourceFile(String relativePath, String pathPackage, String pathTypeName, String content) {}

  private SourceFileReader() {}

  static List<SourceFile> read(Path projectRoot, Charset encoding, List<AnalysisIssue> issues) {
    Path sourceRoot = projectRoot.resolve(SOURCE_ROOT);
    if (!Files.isDirectory(sourceRoot)) {
      return List.of();
    }
    try (Stream<Path> paths = Files.walk(sourceRoot)) {
      return paths
          .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".java"))
          // String order of the relative path: the same on every platform (Path order is not).
          .sorted(Comparator.comparing(p -> slashed(sourceRoot.relativize(p))))
          .map(p -> readOne(projectRoot, sourceRoot, p, encoding, issues))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to list sources under " + sourceRoot, e);
    }
  }

  private static SourceFile readOne(
      Path projectRoot, Path sourceRoot, Path file, Charset encoding, List<AnalysisIssue> issues) {
    String relativePath = slashed(projectRoot.relativize(file));
    String underSourceRoot = slashed(sourceRoot.relativize(file));
    String typePath = underSourceRoot.substring(0, underSourceRoot.length() - ".java".length());
    String pathTypeName = typePath.replace('/', '.');
    int lastDot = pathTypeName.lastIndexOf('.');
    String pathPackage = lastDot < 0 ? "" : pathTypeName.substring(0, lastDot);
    return new SourceFile(
        relativePath, pathPackage, pathTypeName, decode(file, relativePath, encoding, issues));
  }

  private static String decode(
      Path file, String relativePath, Charset encoding, List<AnalysisIssue> issues) {
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(file);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + file, e);
    }
    try {
      return strictDecode(bytes, encoding);
    } catch (CharacterCodingException notDeclaredEncoding) {
      try {
        String content = strictDecode(bytes, FALLBACK_CHARSET);
        issues.add(encodingIssue(relativePath, encoding.name() + " として読み込めないため、MS932 として読み込みました。"));
        return content;
      } catch (CharacterCodingException notFallbackEither) {
        issues.add(
            encodingIssue(
                relativePath, encoding.name() + " / MS932 のいずれでも読み込めないため、不正な文字を置換して読み込みました。"));
        return new String(bytes, encoding);
      }
    }
  }

  private static String strictDecode(byte[] bytes, Charset charset)
      throws CharacterCodingException {
    return charset
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  private static AnalysisIssue encodingIssue(String relativePath, String message) {
    return new AnalysisIssue(
        IssueKind.ENCODING_FALLBACK, IssueSeverity.WARNING, relativePath, null, message);
  }

  private static String slashed(Path path) {
    return path.toString().replace('\\', '/');
  }
}
