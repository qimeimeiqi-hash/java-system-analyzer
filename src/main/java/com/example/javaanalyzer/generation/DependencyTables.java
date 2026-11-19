package com.example.javaanalyzer.generation;

import com.example.javaanalyzer.ir.DependencyModel;
import com.example.javaanalyzer.ir.DependencyResolution;
import com.example.javaanalyzer.ir.ProjectModel;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Dependency tables (FR-08, SCR-03): nothing that the diagram cannot draw is left out. */
public final class DependencyTables {

  /** Resolutions that become an edge of the dependency diagrams (screen-design 2.3). */
  public static final Set<DependencyResolution> DRAWN =
      Set.of(
          DependencyResolution.PROJECT_TYPE,
          DependencyResolution.SINGLE_IMPLEMENTATION,
          DependencyResolution.COLLECTION_ELEMENT);

  private DependencyTables() {}

  /** Dependencies that are not drawn, with the reason. */
  public static Table unresolved(ProjectModel model) {
    return new Table(
        List.of("依存元", "メンバー", "宣言型", "理由"),
        sorted(model.dependencies()).stream()
            .filter(dependency -> !isDrawn(dependency))
            .map(
                dependency ->
                    List.of(
                        Cell.typeName(dependency.fromType()),
                        Cell.of(dependency.memberName()),
                        Cell.type(dependency.declaredType()),
                        Cell.of(Labels.resolution(dependency.resolution()))))
            .toList());
  }

  /** Every dependency, for the exports. */
  public static Table all(ProjectModel model) {
    return new Table(
        List.of("依存元", "メンバー", "注入方式", "宣言型", "解決先", "解決方法"),
        sorted(model.dependencies()).stream()
            .map(
                dependency ->
                    List.of(
                        Cell.typeName(dependency.fromType()),
                        Cell.of(dependency.memberName()),
                        Cell.of(Labels.injectionKind(dependency.injectionKind())),
                        Cell.type(dependency.declaredType()),
                        Cell.typeName(dependency.resolvedType()),
                        Cell.of(Labels.resolution(dependency.resolution()))))
            .toList());
  }

  public static boolean isDrawn(DependencyModel dependency) {
    return DRAWN.contains(dependency.resolution()) && dependency.resolvedType() != null;
  }

  private static List<DependencyModel> sorted(List<DependencyModel> dependencies) {
    return dependencies.stream()
        .sorted(
            Comparator.comparing(DependencyModel::fromType)
                .thenComparing(DependencyModel::memberName)
                .thenComparing(d -> d.resolvedType() == null ? "" : d.resolvedType()))
        .toList();
  }
}
