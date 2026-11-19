package com.example.javaanalyzer.generation;

import static com.example.javaanalyzer.generation.IrFixtures.component;
import static com.example.javaanalyzer.generation.IrFixtures.project;
import static com.example.javaanalyzer.generation.IrFixtures.type;
import static com.example.javaanalyzer.generation.IrFixtures.typeModel;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.ir.AnalysisIssue;
import com.example.javaanalyzer.ir.ComponentRole;
import com.example.javaanalyzer.ir.DependencyModel;
import com.example.javaanalyzer.ir.DependencyResolution;
import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.EntityKind;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.InjectionKind;
import com.example.javaanalyzer.ir.IssueKind;
import com.example.javaanalyzer.ir.IssueSeverity;
import com.example.javaanalyzer.ir.PackageModel;
import com.example.javaanalyzer.ir.RepositoryModel;
import com.example.javaanalyzer.ir.TypeKind;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProjectStatisticsTest {

  private static EndpointModel endpoint(String methodName) {
    return new EndpointModel(
        "com.example.web.OrderController",
        methodName,
        List.of("GET"),
        List.of("/orders"),
        true,
        null,
        null,
        null,
        List.of(),
        true,
        List.of());
  }

  private static EntityModel entity(String typeName, EntityKind kind) {
    return new EntityModel(typeName, kind, null, null, null, null, List.of(), List.of());
  }

  private static DependencyModel dependency(String from, String to) {
    return new DependencyModel(
        from, "member", InjectionKind.CONSTRUCTOR, type(to), to, DependencyResolution.PROJECT_TYPE);
  }

  @Test
  @DisplayName("Counts every SCR-02 item from the IR")
  void countsEveryItem() {
    var model =
        project()
            .packages(
                new PackageModel("com.example.web", List.of(), List.of()),
                new PackageModel("com.example.service", List.of(), List.of()))
            .types(
                typeModel("com.example.A", TypeKind.CLASS),
                typeModel("com.example.B", TypeKind.ENUM),
                typeModel("com.example.C", TypeKind.RECORD),
                typeModel("com.example.D", TypeKind.INTERFACE),
                typeModel("com.example.E", TypeKind.ANNOTATION))
            .components(
                component("com.example.web.OrderController", ComponentRole.CONTROLLER),
                component("com.example.service.OrderService", ComponentRole.SERVICE),
                component("com.example.service.MailService", ComponentRole.SERVICE),
                component("com.example.repo.LegacyDao", ComponentRole.REPOSITORY),
                component("com.example.repo.OrderRepository", ComponentRole.REPOSITORY))
            .repositories(
                new RepositoryModel("com.example.repo.OrderRepository", null, null, null),
                new RepositoryModel("com.example.repo.CustomerRepository", null, null, null))
            .endpoints(endpoint("list"), endpoint("find"), endpoint("create"))
            .entities(
                entity("com.example.Order", EntityKind.ENTITY),
                entity("com.example.Base", EntityKind.MAPPED_SUPERCLASS))
            .dependencies(dependency("com.example.A", "com.example.B"))
            .issues(
                new AnalysisIssue(IssueKind.SYNTAX_ERROR, IssueSeverity.ERROR, "a", 1, "x"),
                new AnalysisIssue(IssueKind.UNRESOLVED_IMPORT, IssueSeverity.WARNING, "b", 2, "y"),
                new AnalysisIssue(
                    IssueKind.CIRCULAR_DEPENDENCY, IssueSeverity.WARNING, null, null, "z"))
            .build();

    ProjectStatistics statistics = ProjectStatistics.of(model);

    assertThat(statistics.packageCount()).isEqualTo(2);
    assertThat(statistics.classCount()).as("CLASS and RECORD").isEqualTo(2);
    assertThat(statistics.interfaceCount()).as("INTERFACE and ANNOTATION").isEqualTo(2);
    assertThat(statistics.enumCount()).isEqualTo(1);
    assertThat(statistics.controllerCount()).isEqualTo(1);
    assertThat(statistics.serviceCount()).isEqualTo(2);
    assertThat(statistics.repositoryCount())
        .as("union of repositories and REPOSITORY components, counted once each")
        .isEqualTo(3);
    assertThat(statistics.apiCount()).isEqualTo(3);
    assertThat(statistics.entityCount()).isEqualTo(2);
    assertThat(statistics.dependencyCount()).isEqualTo(1);
    assertThat(statistics.errorCount()).isEqualTo(1);
    assertThat(statistics.warningCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("An empty project has zero for every item")
  void emptyProjectIsAllZero() {
    ProjectStatistics statistics = ProjectStatistics.of(project().build());

    assertThat(statistics).isEqualTo(new ProjectStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
  }

  @Test
  @DisplayName("Lists the items in SCR-02 order with Japanese labels for the tables and exports")
  void listsLabeledItems() {
    ProjectStatistics statistics = new ProjectStatistics(5, 30, 8, 4, 6, 9, 7, 24, 7, 31, 1, 2);

    assertThat(statistics.items())
        .extracting(ProjectStatistics.Item::label, ProjectStatistics.Item::count)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("パッケージ", 5),
            org.assertj.core.groups.Tuple.tuple("クラス", 30),
            org.assertj.core.groups.Tuple.tuple("インタフェース", 8),
            org.assertj.core.groups.Tuple.tuple("Enum", 4),
            org.assertj.core.groups.Tuple.tuple("Controller", 6),
            org.assertj.core.groups.Tuple.tuple("Service", 9),
            org.assertj.core.groups.Tuple.tuple("Repository", 7),
            org.assertj.core.groups.Tuple.tuple("API", 24),
            org.assertj.core.groups.Tuple.tuple("エンティティ", 7),
            org.assertj.core.groups.Tuple.tuple("依存関係", 31));
  }
}
