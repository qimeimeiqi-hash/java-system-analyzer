package com.example.javaanalyzer.analysis;

import com.example.javaanalyzer.analysis.SourceFileReader.SourceFile;
import com.example.javaanalyzer.analysis.plugin.AnalyzerPlugin;
import com.example.javaanalyzer.ir.AnalysisIssue;
import com.example.javaanalyzer.ir.IssueKind;
import com.example.javaanalyzer.ir.IssueSeverity;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.TypeModel;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * FR-03 / FR-14 entry point: parses production sources of an extracted project (never executes
 * them), extracts framework-independent structure, lets framework plugins add their findings, then
 * aggregates project-wide information (dependencies, packages, cycles).
 */
@Service
public class ProjectAnalyzer {

  private static final int MAX_PROBLEM_MESSAGE_LENGTH = 200;

  private final List<AnalyzerPlugin> plugins;

  public ProjectAnalyzer(List<AnalyzerPlugin> plugins) {
    this.plugins = List.copyOf(plugins);
  }

  public ProjectModel analyze(Path projectRoot) {
    return analyze(projectRoot, StandardCharsets.UTF_8);
  }

  public ProjectModel analyze(Path projectRoot, Charset sourceEncoding) {
    AnalysisContext context = buildContext(projectRoot, sourceEncoding);
    for (AnalyzerPlugin plugin : plugins) {
      if (plugin.supports(context)) {
        plugin.analyze(context);
      }
    }
    ProjectModel model = ProjectModelAggregator.aggregate(context.toProjectModel());
    return CallGraphExtractor.addCalls(model, context.parsedTypes());
  }

  /** Parses sources and extracts framework-independent structure, before any plugin runs. */
  public AnalysisContext buildContext(Path projectRoot, Charset sourceEncoding) {
    List<AnalysisIssue> issues = new ArrayList<>();
    List<SourceFile> sources = SourceFileReader.read(projectRoot, sourceEncoding, issues);

    JavaParser parser =
        new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    Map<String, CompilationUnit> units = new LinkedHashMap<>();
    Set<String> projectTypes = new HashSet<>();
    Set<String> projectPackages = new HashSet<>();
    for (SourceFile source : sources) {
      projectPackages.add(source.pathPackage());
      ParseResult<CompilationUnit> result;
      try {
        result = parser.parse(source.content());
      } catch (StackOverflowError e) {
        // Too deeply nested to parse (e.g. thousands of chained operators); the stack has unwound
        projectTypes.add(source.pathTypeName());
        issues.add(
            new AnalysisIssue(
                IssueKind.SYNTAX_ERROR,
                IssueSeverity.ERROR,
                source.relativePath(),
                null,
                "構文解析に失敗しました。式や文の入れ子が深すぎるため解析できません。"));
        continue;
      }
      if (result.isSuccessful() && result.getResult().isPresent()) {
        CompilationUnit unit = result.getResult().get();
        units.put(source.relativePath(), unit);
        unit.getPackageDeclaration().ifPresent(p -> projectPackages.add(p.getNameAsString()));
        for (TypeDeclaration<?> type : StructureExtractor.declaredTypes(unit)) {
          projectTypes.add(type.getFullyQualifiedName().orElseThrow());
        }
      } else {
        // The type still exists in the project; keep it known so imports of it are not flagged.
        projectTypes.add(source.pathTypeName());
        issues.add(syntaxError(source.relativePath(), result.getProblems()));
      }
    }

    String rootPackage = commonPackage(projectPackages);
    List<ParsedType> parsedTypes = new ArrayList<>();
    List<TypeModel> typeModels = new ArrayList<>();
    Map<String, String> fileOfType = new HashMap<>();
    units.forEach(
        (sourceFile, unit) -> {
          List<TypeDeclaration<?>> declared = StructureExtractor.declaredTypes(unit);
          List<String> declaredNames =
              declared.stream().map(t -> t.getFullyQualifiedName().orElseThrow()).toList();
          TypeNameResolver resolver = new TypeNameResolver(unit, declaredNames, projectTypes);
          for (TypeDeclaration<?> type : declared) {
            String qualifiedName = type.getFullyQualifiedName().orElseThrow();
            String firstFile = fileOfType.putIfAbsent(qualifiedName, sourceFile);
            if (firstFile != null) {
              issues.add(duplicateType(qualifiedName, firstFile, sourceFile));
              continue;
            }
            ParsedType parsed = new ParsedType(qualifiedName, type, sourceFile, resolver);
            parsedTypes.add(parsed);
            typeModels.add(StructureExtractor.extract(parsed, unit));
          }
        });
    issues.addAll(ImportVerifier.verify(units, rootPackage, projectTypes, projectPackages));

    AnalysisContext context =
        new AnalysisContext(projectRoot, rootPackage, parsedTypes, typeModels);
    issues.forEach(context::addIssue);
    return context;
  }

  /**
   * javac rejects a type declared twice, but such copies (backups, leftovers of generated code) do
   * occur in uploads. The first file in path order is analyzed and the other is reported, instead
   * of one silently replacing the other.
   */
  private static AnalysisIssue duplicateType(
      String qualifiedName, String firstFile, String duplicateFile) {
    return new AnalysisIssue(
        IssueKind.DUPLICATE_TYPE,
        IssueSeverity.WARNING,
        duplicateFile,
        null,
        "型 "
            + qualifiedName
            + " は "
            + firstFile
            + " でも宣言されています。先に見つかった "
            + firstFile
            + " のみを解析し、このファイルの宣言は解析結果に含めていません。");
  }

  private static AnalysisIssue syntaxError(String sourceFile, List<Problem> problems) {
    Integer line = null;
    String message = "構文解析に失敗しました。";
    if (!problems.isEmpty()) {
      Problem first = problems.get(0);
      line =
          first
              .getLocation()
              .flatMap(range -> range.getBegin().getRange())
              .map(r -> r.begin.line)
              .orElse(null);
      String detail = first.getMessage().lines().findFirst().orElse("").strip();
      if (detail.length() > MAX_PROBLEM_MESSAGE_LENGTH) {
        detail = detail.substring(0, MAX_PROBLEM_MESSAGE_LENGTH) + "...";
      }
      message += " " + detail + (problems.size() > 1 ? "（他" + (problems.size() - 1) + "件）" : "");
    }
    return new AnalysisIssue(
        IssueKind.SYNTAX_ERROR, IssueSeverity.ERROR, sourceFile, line, message);
  }

  /** Longest common package prefix (whole segments) of all non-default packages. */
  static String commonPackage(Set<String> packages) {
    List<String[]> segmentsList =
        packages.stream().filter(p -> !p.isEmpty()).map(p -> p.split("\\.")).toList();
    if (segmentsList.isEmpty()) {
      return "";
    }
    String[] first = segmentsList.get(0);
    int common = first.length;
    for (String[] segments : segmentsList) {
      int i = 0;
      while (i < common && i < segments.length && segments[i].equals(first[i])) {
        i++;
      }
      common = i;
    }
    return String.join(".", Arrays.copyOf(first, common));
  }
}
