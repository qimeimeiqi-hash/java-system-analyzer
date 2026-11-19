package com.example.javaanalyzer.analysis.plugin.springboot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.example.javaanalyzer.analysis.AnalysisContext;
import com.example.javaanalyzer.analysis.AnalysisTestSupport;
import com.example.javaanalyzer.ir.ComponentModel;
import com.example.javaanalyzer.ir.ComponentRole;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.RepositoryModel;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpringDataRepositoryAnalyzerTest {

  @TempDir Path projectRoot;

  private ProjectModel analyze(String... sources) {
    AnalysisContext context = AnalysisTestSupport.contextOf(projectRoot, sources);
    new SpringComponentAnalyzer().analyze(context);
    new SpringDataRepositoryAnalyzer().analyze(context);
    return context.toProjectModel();
  }

  private static final String ORDER_ENTITY =
      """
      package com.example.domain;
      public class Order {}
      """;

  @Test
  @DisplayName("An interface extending a type that cannot be determined is skipped without failing")
  void skipsInterfaceExtendingUndeterminedType() {
    ProjectModel model =
        analyze(
            """
            package com.example;
            import com.other.*;
            public interface Custom extends SomeBase {}
            """);

    assertThat(model.repositories()).isEmpty();
    assertThat(model.types()).hasSize(1);
  }

  @Test
  @DisplayName("JpaRepository imported on demand is detected with its qualified base type")
  void detectsOnDemandImportedRepository() {
    ProjectModel model =
        analyze(
            ORDER_ENTITY,
            """
            package com.example.domain;
            import org.springframework.data.jpa.repository.*;
            public interface OrderRepository extends JpaRepository<Order, Long> {}
            """);

    assertThat(model.repositories()).hasSize(1);
    RepositoryModel repository = model.repositories().get(0);
    assertThat(repository.baseType().qualifiedName())
        .isEqualTo("org.springframework.data.jpa.repository.JpaRepository");
    assertThat(repository.entityType().qualifiedName()).isEqualTo("com.example.domain.Order");
    assertThat(model.components())
        .extracting(ComponentModel::typeName, ComponentModel::role)
        .containsExactly(tuple("com.example.domain.OrderRepository", ComponentRole.REPOSITORY));
  }

  @Test
  @DisplayName("A JpaRepository from an unrelated on-demand import is not a repository")
  void ignoresRepositoryNameFromUnrelatedImport() {
    ProjectModel model =
        analyze(
            ORDER_ENTITY,
            """
            package com.example.domain;
            import com.other.*;
            public interface OrderRepository extends JpaRepository<Order, Long> {}
            """);

    assertThat(model.repositories()).isEmpty();
  }

  @Test
  @DisplayName("JpaRepository<Order, Long> yields entity and id types and a REPOSITORY component")
  void detectsJpaRepository() {
    ProjectModel model =
        analyze(
            ORDER_ENTITY,
            """
            package com.example.repository;
            import com.example.domain.Order;
            import org.springframework.data.jpa.repository.JpaRepository;
            public interface OrderRepository extends JpaRepository<Order, Long> {}
            """);

    assertThat(model.repositories()).hasSize(1);
    RepositoryModel repository = model.repositories().get(0);
    assertThat(repository.typeName()).isEqualTo("com.example.repository.OrderRepository");
    assertThat(repository.baseType().qualifiedName())
        .isEqualTo("org.springframework.data.jpa.repository.JpaRepository");
    assertThat(repository.entityType().qualifiedName()).isEqualTo("com.example.domain.Order");
    assertThat(repository.idType().qualifiedName()).isEqualTo("java.lang.Long");
    assertThat(model.components())
        .extracting(ComponentModel::typeName, ComponentModel::role)
        .containsExactly(tuple("com.example.repository.OrderRepository", ComponentRole.REPOSITORY));
  }

  @Test
  @DisplayName("CrudRepository with @Repository is registered once as a component")
  void doesNotDuplicateAnnotatedRepositoryComponent() {
    ProjectModel model =
        analyze(
            ORDER_ENTITY,
            """
            package com.example.repository;
            import com.example.domain.Order;
            import org.springframework.data.repository.CrudRepository;
            import org.springframework.stereotype.Repository;
            @Repository
            public interface OrderRepository extends CrudRepository<Order, String> {}
            """);

    assertThat(model.repositories())
        .singleElement()
        .satisfies(r -> assertThat(r.idType().qualifiedName()).isEqualTo("java.lang.String"));
    assertThat(model.components()).hasSize(1);
  }

  @Test
  @DisplayName("Interfaces not extending Spring Data repositories are ignored")
  void ignoresOtherInterfaces() {
    ProjectModel model =
        analyze(
            ORDER_ENTITY,
            """
            package com.example.repository;
            import com.example.domain.Order;
            import java.util.List;
            public interface OrderFinder extends Comparable<Order> { List<Order> findAll(); }
            """);

    assertThat(model.repositories()).isEmpty();
    assertThat(model.components()).isEmpty();
  }

  @Test
  @DisplayName("A raw Repository without type arguments has null entity and id types")
  void rawRepositoryHasNullTypes() {
    ProjectModel model =
        analyze(
            """
            package com.example.repository;
            import org.springframework.data.repository.Repository;
            @SuppressWarnings("rawtypes")
            public interface RawRepository extends Repository {}
            """);

    assertThat(model.repositories())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.entityType()).isNull();
              assertThat(r.idType()).isNull();
            });
  }
}
