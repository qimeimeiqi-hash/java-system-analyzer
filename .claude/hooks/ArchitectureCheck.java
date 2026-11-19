import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Checks the architecture principles of CLAUDE.md (WBS No.13) on the production sources.
 *
 * <p>Run as a single-file program with JDK 21 ({@code java ArchitectureCheck.java <mode>}); it has no
 * dependencies. Modes:
 *
 * <ul>
 *   <li>{@code --post-edit}: Claude Code PostToolUse hook. Reads the hook JSON from stdin and checks
 *       the edited file (the whole tree for multi-file tools). Exit 2 with the report on stderr when a
 *       rule is violated, so that Claude receives it as feedback.
 *   <li>{@code --pre-commit}: Claude Code PreToolUse hook for Bash/PowerShell. When the command runs
 *       {@code git commit}, checks the whole tree and exits 2 (blocks the command) on a violation.
 *   <li>{@code --all [projectDir]}: checks every file under {@code src/main/java}. Exit 1 on a
 *       violation.
 *   <li>{@code --files <file>...}: checks the given files. Exit 1 on a violation.
 * </ul>
 *
 * <p>Rules (layer is taken from the {@code package} declaration, references from imports and fully
 * qualified names with comments and literals removed):
 *
 * <ul>
 *   <li>R1: generation must not refer to analysis, upload, web or JavaParser (IR separation).
 *   <li>R2: generation must not read or write files directly (IR separation).
 *   <li>R3: ir must not refer to analysis, generation, upload, web, JavaParser or Spring.
 *   <li>R4: a plugin under analysis.plugin must not refer to another plugin (NFR-05).
 *   <li>R5: the analysis core must not refer to a concrete plugin, only to AnalyzerPlugin (NFR-05).
 *   <li>R6: upload and analysis must not refer to each other nor to generation/web; JavaParser is
 *       used only inside analysis.
 * </ul>
 *
 * <p>Known limitations: references through reflection or strings ({@code Class.forName}), resources
 * read through {@code getResourceAsStream}, and types used without being named (e.g. {@code var} of a
 * method result) are not detected. The pre-commit check reads the working tree, not the index. Files
 * are read as UTF-8.
 */
public class ArchitectureCheck {

  private static final String ROOT = "com.example.javaanalyzer";

  private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+([\\w.\\s]+?)\\s*;");
  private static final String CHAIN = "(?:\\s*\\.\\s*(?:\\w+|\\*))*";
  private static final Pattern PROJECT_REF =
      Pattern.compile("\\bcom\\s*\\.\\s*example\\s*\\.\\s*javaanalyzer" + CHAIN);
  private static final Pattern JAVAPARSER_REF =
      Pattern.compile("\\bcom\\s*\\.\\s*github\\s*\\.\\s*javaparser\\b" + CHAIN);
  private static final Pattern SPRING_REF =
      Pattern.compile("\\borg\\s*\\.\\s*springframework\\b" + CHAIN);
  private static final Pattern FILE_IO_REF =
      Pattern.compile(
          "\\bjava\\s*\\.\\s*nio\\s*\\.\\s*file\\b"
              + CHAIN
              + "|\\bjava\\s*\\.\\s*io\\s*\\.\\s*(?:File\\w*|RandomAccessFile)\\b");
  private static final Pattern JAVA_IO_WILDCARD =
      Pattern.compile("\\bimport\\s+java\\s*\\.\\s*io\\s*\\.\\s*\\*\\s*;");
  private static final Pattern FILE_IO_SIMPLE_NAME =
      Pattern.compile(
          "(?<![\\w.])(?:File|FileReader|FileInputStream|FileWriter|FileOutputStream"
              + "|RandomAccessFile)\\b");
  private static final Pattern GIT_COMMIT = Pattern.compile("\\bgit\\b[^;&|\\n]*\\bcommit\\b");

  /** Serena tools that may change files other than the one named in their input. */
  private static final Set<String> MULTI_FILE_TOOLS =
      Set.of("mcp__serena__rename_symbol", "mcp__serena__replace_in_files");

  record Violation(int line, String rule, String message) {}

  private static PrintStream out;
  private static PrintStream err;

  public static void main(String[] args) throws IOException {
    out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
    String mode = args.length == 0 ? "" : args[0];
    int exitCode =
        switch (mode) {
          case "--post-edit" -> postEdit();
          case "--pre-commit" -> preCommit();
          case "--all" -> all(args.length > 1 ? Path.of(args[1]) : projectDir(Map.of()));
          case "--files" -> files(Arrays.asList(args).subList(1, args.length));
          default -> {
            err.println(
                "usage: java ArchitectureCheck.java --post-edit | --pre-commit | --all [dir]"
                    + " | --files <file>...");
            yield 64;
          }
        };
    System.exit(exitCode);
  }

  // ---- modes ----

  private static int postEdit() throws IOException {
    Map<String, Object> input = readHookInput();
    Path projectDir = projectDir(input);
    Object toolInput = input.get("tool_input");
    Map<?, ?> params = toolInput instanceof Map<?, ?> m ? m : Map.of();
    Object toolName = input.get("tool_name");
    Object pathValue = params.get("file_path");
    if (pathValue == null) {
      pathValue = params.get("relative_path");
    }
    Map<Path, List<Violation>> report;
    if (pathValue == null || MULTI_FILE_TOOLS.contains(toolName)) {
      report = checkTree(projectDir);
    } else {
      Path file = projectDir.resolve(pathValue.toString()).normalize();
      if (!isProductionSource(file) || !Files.isRegularFile(file)) {
        return 0;
      }
      report = checkFiles(List.of(file));
    }
    if (report.isEmpty()) {
      return 0;
    }
    printReport(err, projectDir, report);
    err.println("上記の違反を修正すること（CLAUDE.md「アーキテクチャ設計方針」）。");
    return 2;
  }

  private static int preCommit() throws IOException {
    Map<String, Object> input = readHookInput();
    Object toolInput = input.get("tool_input");
    Object command = toolInput instanceof Map<?, ?> m ? m.get("command") : null;
    if (command == null || !GIT_COMMIT.matcher(command.toString()).find()) {
      return 0;
    }
    Path projectDir = projectDir(input);
    Map<Path, List<Violation>> report = checkTree(projectDir);
    if (report.isEmpty()) {
      return 0;
    }
    printReport(err, projectDir, report);
    err.println("アーキテクチャ原則違反があるためコミットを中止した。違反を修正してから再度コミットすること。");
    return 2;
  }

  private static int all(Path projectDir) throws IOException {
    Map<Path, List<Violation>> report = checkTree(projectDir);
    if (report.isEmpty()) {
      out.println("アーキテクチャ原則違反なし（" + sourcesOf(projectDir).size() + "ファイル）");
      return 0;
    }
    printReport(out, projectDir, report);
    return 1;
  }

  private static int files(List<String> names) throws IOException {
    List<Path> paths = names.stream().map(Path::of).toList();
    Map<Path, List<Violation>> report = checkFiles(paths);
    if (report.isEmpty()) {
      return 0;
    }
    printReport(out, Path.of("").toAbsolutePath(), report);
    return 1;
  }

  // ---- checking ----

  private static Map<Path, List<Violation>> checkTree(Path projectDir) throws IOException {
    return checkFiles(sourcesOf(projectDir));
  }

  private static List<Path> sourcesOf(Path projectDir) throws IOException {
    Path sourceRoot = projectDir.resolve("src/main/java");
    if (!Files.isDirectory(sourceRoot)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(sourceRoot)) {
      return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    }
  }

  private static Map<Path, List<Violation>> checkFiles(List<Path> files) throws IOException {
    Map<Path, List<Violation>> report = new LinkedHashMap<>();
    for (Path file : files) {
      List<Violation> violations = check(readUtf8(file));
      if (!violations.isEmpty()) {
        report.put(file, violations);
      }
    }
    return report;
  }

  static List<Violation> check(String source) {
    String code = mask(source);
    Matcher packageMatcher = PACKAGE.matcher(code);
    if (!packageMatcher.find()) {
      return List.of();
    }
    String sourcePackage = relativePackage(squeeze(packageMatcher.group(1)));
    if (sourcePackage == null) {
      return List.of();
    }
    int[] lineStarts = lineStarts(code);
    Set<Violation> violations = new LinkedHashSet<>();

    Matcher project = PROJECT_REF.matcher(code);
    while (project.find()) {
      String reference = squeeze(project.group());
      String target = relativePackage(packageOf(reference));
      String message = projectRule(sourcePackage, target, reference);
      if (message != null) {
        violations.add(
            new Violation(lineOf(lineStarts, project.start()), message.substring(0, 2), message));
      }
    }
    collect(code, lineStarts, JAVAPARSER_REF, violations, ref -> javaParserRule(sourcePackage, ref));
    collect(code, lineStarts, SPRING_REF, violations, ref -> springRule(sourcePackage, ref));
    if (under(sourcePackage, "generation")) {
      collect(code, lineStarts, FILE_IO_REF, violations, ArchitectureCheck::fileIoMessage);
      if (JAVA_IO_WILDCARD.matcher(code).find()) {
        collect(
            code,
            lineStarts,
            FILE_IO_SIMPLE_NAME,
            violations,
            ref -> fileIoMessage("java.io.* の " + ref));
      }
    }
    List<Violation> sorted = new ArrayList<>(violations);
    sorted.sort((a, b) -> Integer.compare(a.line(), b.line()));
    return sorted;
  }

  private static void collect(
      String code,
      int[] lineStarts,
      Pattern pattern,
      Set<Violation> violations,
      java.util.function.Function<String, String> rule) {
    Matcher matcher = pattern.matcher(code);
    while (matcher.find()) {
      String message = rule.apply(squeeze(matcher.group()));
      if (message != null) {
        violations.add(
            new Violation(lineOf(lineStarts, matcher.start()), message.substring(0, 2), message));
      }
    }
  }

  // ---- rules (each message starts with its rule ID) ----

  private static String projectRule(String source, String target, String reference) {
    if (target == null) {
      return null;
    }
    if (under(source, "generation")
        && (under(target, "analysis") || under(target, "upload") || under(target, "web"))) {
      return "R1 IR分離（設計方針2）：generation は "
          + display(target)
          + " を参照できない（"
          + reference
          + "）。生成層が参照できるのは ir / ir.io のみ。必要な値がIRに無い場合はCLAUDE.md No.12「IRの変更」に従う";
    }
    if (under(source, "ir")
        && (under(target, "analysis")
            || under(target, "generation")
            || under(target, "upload")
            || under(target, "web"))) {
      return "R3 IR分離（設計方針2）：ir は "
          + display(target)
          + " を参照できない（"
          + reference
          + "）。IRは解析層・生成層から独立したフレームワーク非依存の表現であること";
    }
    String sourcePlugin = pluginOf(source);
    String targetPlugin = pluginOf(target);
    if (sourcePlugin != null && targetPlugin != null && !sourcePlugin.equals(targetPlugin)) {
      return "R4 プラグイン化（設計方針1・NFR-05）：プラグイン "
          + sourcePlugin
          + " は別のプラグイン "
          + targetPlugin
          + " を参照できない（"
          + reference
          + "）。共通の処理は analysis（コア）に置くこと";
    }
    if (under(source, "analysis") && sourcePlugin == null && targetPlugin != null) {
      return "R5 プラグイン化（設計方針1・NFR-05）：analysis のコアは具体的なプラグイン "
          + targetPlugin
          + " を参照できない（"
          + reference
          + "）。プラグインは AnalyzerPlugin を介して呼び出すこと";
    }
    boolean fromUpload = under(source, "upload");
    boolean fromAnalysis = under(source, "analysis");
    if ((fromUpload || fromAnalysis)
        && (under(target, "generation")
            || under(target, "web")
            || (fromUpload && under(target, "analysis"))
            || (fromAnalysis && under(target, "upload")))) {
      return "R6 層の独立性：" + display(source) + " は " + display(target) + " を参照できない（"
          + reference
          + "）。upload と analysis は互いに独立し、上位層（generation / web）に依存しないこと";
    }
    return null;
  }

  private static String javaParserRule(String source, String reference) {
    if (under(source, "generation")) {
      return "R1 IR分離（設計方針2）：generation は JavaParser を参照できない（"
          + reference
          + "）。生成層はソースコード・ASTを直接扱わず、IRのみを参照すること";
    }
    if (under(source, "ir")) {
      return "R3 IR分離（設計方針2）：ir は JavaParser を参照できない（"
          + reference
          + "）。IRはASTから独立した表現であること";
    }
    if (!under(source, "analysis")) {
      return "R6 層の独立性：" + display(source) + " は JavaParser を参照できない（"
          + reference
          + "）。JavaParser は analysis の中でのみ使うこと";
    }
    return null;
  }

  private static String springRule(String source, String reference) {
    if (under(source, "ir")) {
      return "R3 IR分離（設計方針2）：ir は Spring を参照できない（"
          + reference
          + "）。IRはフレームワーク非依存であること（Springの知識は springboot プラグインに置く）";
    }
    return null;
  }

  private static String fileIoMessage(String reference) {
    return "R2 IR分離（設計方針2）：generation はファイルを直接読み書きできない（"
        + reference
        + "）。IRの読み込みは呼び出し側（web）が IrReader で行い、生成器は ProjectModel を受け取ること";
  }

  // ---- package helpers ----

  /** Package part of a qualified reference: segments up to the first type name or wildcard. */
  private static String packageOf(String reference) {
    StringBuilder packageName = new StringBuilder();
    for (String segment : reference.split("\\.")) {
      if (segment.equals("*") || Character.isUpperCase(segment.charAt(0))) {
        break;
      }
      if (!packageName.isEmpty()) {
        packageName.append('.');
      }
      packageName.append(segment);
    }
    return packageName.toString();
  }

  /** Package relative to the project root ("" for the root itself), or null when outside it. */
  private static String relativePackage(String packageName) {
    if (packageName.equals(ROOT)) {
      return "";
    }
    return packageName.startsWith(ROOT + ".") ? packageName.substring(ROOT.length() + 1) : null;
  }

  private static boolean under(String packageName, String base) {
    return packageName.equals(base) || packageName.startsWith(base + ".");
  }

  /** Name of the plugin ("mybatis" etc.) the package belongs to, or null. */
  private static String pluginOf(String packageName) {
    String prefix = "analysis.plugin.";
    if (!packageName.startsWith(prefix)) {
      return null;
    }
    String rest = packageName.substring(prefix.length());
    int dot = rest.indexOf('.');
    return dot < 0 ? rest : rest.substring(0, dot);
  }

  private static String display(String relativePackage) {
    return relativePackage.isEmpty() ? ROOT : relativePackage;
  }

  // ---- text helpers ----

  /** Replaces comments and string/char/text-block literals with spaces, keeping line breaks. */
  static String mask(String source) {
    StringBuilder masked = new StringBuilder(source.length());
    int length = source.length();
    int i = 0;
    while (i < length) {
      char c = source.charAt(i);
      int end;
      if (source.startsWith("//", i)) {
        end = source.indexOf('\n', i);
        end = end < 0 ? length : end;
      } else if (source.startsWith("/*", i)) {
        end = source.indexOf("*/", i + 2);
        end = end < 0 ? length : end + 2;
      } else if (source.startsWith("\"\"\"", i)) {
        end = length;
        for (int j = i + 3; j < length; j++) {
          if (source.charAt(j) == '\\') {
            j++;
          } else if (source.startsWith("\"\"\"", j)) {
            end = j + 3;
            break;
          }
        }
      } else if (c == '"' || c == '\'') {
        int j = i + 1;
        while (j < length && source.charAt(j) != c && source.charAt(j) != '\n') {
          j += source.charAt(j) == '\\' ? 2 : 1;
        }
        end = Math.min(j + 1, length);
      } else {
        masked.append(c);
        i++;
        continue;
      }
      for (int k = i; k < end; k++) {
        masked.append(source.charAt(k) == '\n' ? '\n' : ' ');
      }
      i = end;
    }
    return masked.toString();
  }

  private static String squeeze(String text) {
    return text.replaceAll("\\s+", "");
  }

  private static int[] lineStarts(String text) {
    List<Integer> starts = new ArrayList<>();
    starts.add(0);
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        starts.add(i + 1);
      }
    }
    return starts.stream().mapToInt(Integer::intValue).toArray();
  }

  private static int lineOf(int[] lineStarts, int offset) {
    int index = Arrays.binarySearch(lineStarts, offset);
    return (index >= 0 ? index : -index - 2) + 1;
  }

  private static String readUtf8(Path file) throws IOException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
        .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file)))
        .toString();
  }

  private static boolean isProductionSource(Path file) {
    if (!file.toString().endsWith(".java")) {
      return false;
    }
    List<String> names = new ArrayList<>();
    file.forEach(name -> names.add(name.toString()));
    return String.join("/", names).contains("src/main/java/");
  }

  private static void printReport(PrintStream stream, Path projectDir, Map<Path, List<Violation>> report) {
    for (Map.Entry<Path, List<Violation>> entry : report.entrySet()) {
      stream.println("[アーキテクチャ原則違反] " + shown(projectDir, entry.getKey()));
      for (Violation violation : entry.getValue()) {
        stream.println(
            "  " + violation.line() + "行目 [" + violation.rule() + "] " + violation.message().substring(3));
      }
    }
  }

  private static String shown(Path projectDir, Path file) {
    Path absolute = file.toAbsolutePath().normalize();
    Path base = projectDir.toAbsolutePath().normalize();
    Path shown = absolute.startsWith(base) ? base.relativize(absolute) : absolute;
    return shown.toString().replace('\\', '/');
  }

  // ---- hook input ----

  private static Map<String, Object> readHookInput() throws IOException {
    String json = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    Object parsed;
    try {
      parsed = new JsonParser(json).parse();
    } catch (RuntimeException e) {
      // Not a hook invocation we understand; never block the tool because of it.
      return Map.of();
    }
    if (!(parsed instanceof Map<?, ?>)) {
      return Map.of();
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> map = (Map<String, Object>) parsed;
    return map;
  }

  private static Path projectDir(Map<String, Object> input) {
    String dir = System.getenv("CLAUDE_PROJECT_DIR");
    if (dir == null || dir.isBlank()) {
      Object cwd = input.get("cwd");
      dir = cwd == null ? "" : cwd.toString();
    }
    return Path.of(dir).toAbsolutePath().normalize();
  }

  /** Minimal JSON parser for the hook input (objects, arrays, strings, numbers, literals). */
  static final class JsonParser {
    private final String text;
    private int position;

    JsonParser(String text) {
      this.text = text;
    }

    Object parse() {
      skipWhitespace();
      if (position >= text.length()) {
        return null;
      }
      Object value = value();
      return value;
    }

    private Object value() {
      skipWhitespace();
      char c = text.charAt(position);
      if (c == '{') {
        Map<String, Object> map = new LinkedHashMap<>();
        position++;
        skipWhitespace();
        if (text.charAt(position) == '}') {
          position++;
          return map;
        }
        while (true) {
          skipWhitespace();
          String key = string();
          skipWhitespace();
          expect(':');
          map.put(key, value());
          skipWhitespace();
          if (text.charAt(position) == ',') {
            position++;
            continue;
          }
          expect('}');
          return map;
        }
      }
      if (c == '[') {
        List<Object> list = new ArrayList<>();
        position++;
        skipWhitespace();
        if (text.charAt(position) == ']') {
          position++;
          return list;
        }
        while (true) {
          list.add(value());
          skipWhitespace();
          if (text.charAt(position) == ',') {
            position++;
            continue;
          }
          expect(']');
          return list;
        }
      }
      if (c == '"') {
        return string();
      }
      int start = position;
      while (position < text.length() && ",}] \t\r\n".indexOf(text.charAt(position)) < 0) {
        position++;
      }
      String literal = text.substring(start, position);
      return switch (literal) {
        case "true" -> Boolean.TRUE;
        case "false" -> Boolean.FALSE;
        case "null" -> null;
        default -> literal;
      };
    }

    private String string() {
      expect('"');
      StringBuilder builder = new StringBuilder();
      while (true) {
        char c = text.charAt(position++);
        if (c == '"') {
          return builder.toString();
        }
        if (c != '\\') {
          builder.append(c);
          continue;
        }
        char escaped = text.charAt(position++);
        switch (escaped) {
          case 'n' -> builder.append('\n');
          case 't' -> builder.append('\t');
          case 'r' -> builder.append('\r');
          case 'b' -> builder.append('\b');
          case 'f' -> builder.append('\f');
          case 'u' -> {
            builder.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
            position += 4;
          }
          default -> builder.append(escaped);
        }
      }
    }

    private void expect(char expected) {
      if (text.charAt(position) != expected) {
        throw new IllegalArgumentException("invalid JSON at " + position);
      }
      position++;
    }

    private void skipWhitespace() {
      while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
        position++;
      }
    }
  }
}
