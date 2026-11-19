package com.example.javaanalyzer.generation;

import com.example.javaanalyzer.ir.ComponentModel;
import com.example.javaanalyzer.ir.ComponentRole;
import com.example.javaanalyzer.ir.IssueSeverity;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.RepositoryModel;
import com.example.javaanalyzer.ir.TypeKind;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Project statistics shown on SCR-02 (FR-13), counted from the IR only.
 *
 * <p>Classes are CLASS and RECORD types, interfaces are INTERFACE and ANNOTATION types (an
 * annotation type is an interface in Java) and enums are counted separately, so the three add up to
 * all types (confirmed by the user on 2026/11/11). Repositories are the union of Spring Data
 * repositories and {@code @Repository} components. Entities count every JPA type of {@code
 * entities}, including mapped superclasses and embeddables.
 */
public record ProjectStatistics(
    int packageCount,
    int classCount,
    int interfaceCount,
    int enumCount,
    int controllerCount,
    int serviceCount,
    int repositoryCount,
    int apiCount,
    int entityCount,
    int dependencyCount,
    int errorCount,
    int warningCount) {

  /** One labeled count, in the order shown on screens and in exports. */
  public record Item(String label, int count) {}

  public static ProjectStatistics of(ProjectModel model) {
    int interfaces =
        (int)
            model.types().stream()
                .filter(t -> t.kind() == TypeKind.INTERFACE || t.kind() == TypeKind.ANNOTATION)
                .count();
    int enums = (int) model.types().stream().filter(t -> t.kind() == TypeKind.ENUM).count();
    Set<String> repositories = new HashSet<>();
    model.repositories().stream().map(RepositoryModel::typeName).forEach(repositories::add);
    model.components().stream()
        .filter(c -> c.role() == ComponentRole.REPOSITORY)
        .map(ComponentModel::typeName)
        .forEach(repositories::add);
    int errors =
        (int) model.issues().stream().filter(i -> i.severity() == IssueSeverity.ERROR).count();
    return new ProjectStatistics(
        model.packages().size(),
        model.types().size() - interfaces - enums,
        interfaces,
        enums,
        countRole(model, ComponentRole.CONTROLLER),
        countRole(model, ComponentRole.SERVICE),
        repositories.size(),
        model.endpoints().size(),
        model.entities().size(),
        model.dependencies().size(),
        errors,
        model.issues().size() - errors);
  }

  /** The SCR-02 statistics block; issue counts are shown separately. */
  public List<Item> items() {
    return List.of(
        new Item("パッケージ", packageCount),
        new Item("クラス", classCount),
        new Item("インタフェース", interfaceCount),
        new Item("Enum", enumCount),
        new Item("Controller", controllerCount),
        new Item("Service", serviceCount),
        new Item("Repository", repositoryCount),
        new Item("API", apiCount),
        new Item("エンティティ", entityCount),
        new Item("依存関係", dependencyCount));
  }

  private static int countRole(ProjectModel model, ComponentRole role) {
    return (int) model.components().stream().filter(c -> c.role() == role).count();
  }
}
