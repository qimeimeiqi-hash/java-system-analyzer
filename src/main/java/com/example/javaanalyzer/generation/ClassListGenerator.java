package com.example.javaanalyzer.generation;

import com.example.javaanalyzer.ir.ComponentModel;
import com.example.javaanalyzer.ir.ComponentRole;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.RepositoryModel;
import com.example.javaanalyzer.ir.TypeModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** The class list of SCR-06 and the role of a class shared by the class pages. */
public final class ClassListGenerator {

  static final String ENTITY_ROLE = "Entity";

  private static final List<String> ROLE_ORDER =
      List.of("Controller", "Service", "Repository", "Configuration", "Component", ENTITY_ROLE);

  private ClassListGenerator() {}

  /**
   * @param packageName only this package; null for all
   * @param role only this role label (see {@link #roles}); null for all
   */
  public static Table table(ProjectModel model, String packageName, String role) {
    List<List<Cell>> rows = new ArrayList<>();
    for (TypeModel type : sorted(model.types())) {
      String typeRole = role(model, type.qualifiedName()).orElse(Labels.DASH);
      if ((packageName != null && !packageName.equals(type.packageName()))
          || (role != null && !role.equals(typeRole))) {
        continue;
      }
      rows.add(
          List.of(
              new Cell(type.simpleName(), type.qualifiedName()),
              Cell.of(Labels.typeKind(type.kind())),
              Cell.of(typeRole),
              Cell.of(type.packageName()),
              Cell.of(firstLine(type.javadoc()))));
    }
    return new Table(List.of("クラス名", "種類", "役割", "パッケージ", "概要"), rows);
  }

  /** The role labels present in the project, in a fixed order. */
  public static List<String> roles(ProjectModel model) {
    List<String> present =
        model.types().stream()
            .map(type -> role(model, type.qualifiedName()))
            .flatMap(Optional::stream)
            .distinct()
            .toList();
    return ROLE_ORDER.stream().filter(present::contains).toList();
  }

  /** The packages that contain types, sorted. */
  public static List<String> packages(ProjectModel model) {
    return model.types().stream().map(TypeModel::packageName).distinct().sorted().toList();
  }

  /** Component role, else Spring Data repository, else JPA entity type. */
  static Optional<String> role(ProjectModel model, String typeName) {
    for (ComponentModel component : model.components()) {
      if (component.typeName().equals(typeName)) {
        return Optional.of(Labels.role(component.role()));
      }
    }
    for (RepositoryModel repository : model.repositories()) {
      if (repository.typeName().equals(typeName)) {
        return Optional.of(Labels.role(ComponentRole.REPOSITORY));
      }
    }
    for (EntityModel entity : model.entities()) {
      if (entity.typeName().equals(typeName)) {
        return Optional.of(ENTITY_ROLE);
      }
    }
    return Optional.empty();
  }

  static List<TypeModel> sorted(List<TypeModel> types) {
    return types.stream()
        .sorted(Comparator.comparing(TypeModel::packageName).thenComparing(TypeModel::simpleName))
        .toList();
  }

  /** The first non-blank line of a Javadoc, or "-". */
  static String firstLine(String javadoc) {
    if (javadoc == null) {
      return Labels.DASH;
    }
    return javadoc
        .lines()
        .map(String::strip)
        .filter(line -> !line.isEmpty())
        .findFirst()
        .orElse(Labels.DASH);
  }
}
