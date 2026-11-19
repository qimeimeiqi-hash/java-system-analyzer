package com.example.javaanalyzer.analysis;

import com.example.javaanalyzer.ir.AnalysisIssue;
import com.example.javaanalyzer.ir.IssueKind;
import com.example.javaanalyzer.ir.IssueSeverity;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects imports that cannot be resolved (FR-14). Without a classpath only imports under the
 * project's own root package can be verified; imports of external libraries are not judged.
 */
final class ImportVerifier {

  private ImportVerifier() {}

  /**
   * @param units parsed compilation units keyed by source path relative to the project root
   */
  static List<AnalysisIssue> verify(
      Map<String, CompilationUnit> units,
      String rootPackage,
      Set<String> projectTypes,
      Set<String> projectPackages) {
    List<AnalysisIssue> issues = new ArrayList<>();
    if (rootPackage.isEmpty()) {
      return issues;
    }
    units.forEach(
        (sourceFile, unit) -> {
          for (ImportDeclaration importDeclaration : unit.getImports()) {
            String name = importDeclaration.getNameAsString();
            if (isUnderRootPackage(name, rootPackage)
                && !exists(importDeclaration, projectTypes, projectPackages)) {
              issues.add(
                  new AnalysisIssue(
                      IssueKind.UNRESOLVED_IMPORT,
                      IssueSeverity.ERROR,
                      sourceFile,
                      importDeclaration.getBegin().map(p -> p.line).orElse(null),
                      "import "
                          + (importDeclaration.isStatic() ? "static " : "")
                          + name
                          + (importDeclaration.isAsterisk() ? ".*" : "")
                          + " を解決できません（プロジェクト内に該当するクラス/パッケージがありません）。"));
            }
          }
        });
    return issues;
  }

  private static boolean isUnderRootPackage(String name, String rootPackage) {
    return name.equals(rootPackage) || name.startsWith(rootPackage + ".");
  }

  private static boolean exists(
      ImportDeclaration importDeclaration, Set<String> projectTypes, Set<String> projectPackages) {
    String name = importDeclaration.getNameAsString();
    if (importDeclaration.isStatic()) {
      String owner =
          importDeclaration.isAsterisk() ? name : name.substring(0, name.lastIndexOf('.'));
      return projectTypes.contains(owner);
    }
    if (importDeclaration.isAsterisk()) {
      return projectPackages.contains(name) || projectTypes.contains(name);
    }
    return projectTypes.contains(name);
  }
}
