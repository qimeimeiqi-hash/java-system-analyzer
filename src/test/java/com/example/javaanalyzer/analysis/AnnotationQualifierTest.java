package com.example.javaanalyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.ir.AnnotationModel;
import com.example.javaanalyzer.ir.BeanDefinitionModel;
import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.ParameterModel;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.TypeModel;
import com.example.javaanalyzer.ir.TypeRef;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnnotationQualifierTest {

  @TempDir Path projectRoot;

  private static final Set<String> KNOWN =
      Set.of(
          "lombok.Getter",
          "lombok.Builder",
          "jakarta.persistence.Entity",
          "javax.persistence.Entity",
          "jakarta.persistence.Id",
          "jakarta.persistence.Column",
          "org.springframework.web.bind.annotation.GetMapping",
          "org.springframework.web.bind.annotation.PathVariable");

  private static final String WILDCARD_SOURCE =
      """
      package com.example.domain;
      import lombok.*;
      import jakarta.persistence.*;
      import org.springframework.web.bind.annotation.*;
      @Entity
      @Getter
      @Unknown
      public class Post {
        @Id @Column(name = "post_id") private Long id;
        @Builder Post(@NotKnown String title) {}
        @GetMapping("/{id}") Object get(@PathVariable Long id) { return null; }
      }
      """;

  private static String qualifiedNameOf(List<AnnotationModel> annotations, String name) {
    return annotations.stream()
        .filter(a -> a.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No @" + name))
        .qualifiedName();
  }

  @Test
  @DisplayName("Before qualification, wildcard-imported annotations have no qualified name")
  void wildcardAnnotationsAreUnresolvedByStructureExtraction() {
    TypeModel post =
        AnalysisTestSupport.contextOf(projectRoot, WILDCARD_SOURCE).type("com.example.domain.Post");

    assertThat(qualifiedNameOf(post.annotations(), "Entity")).isNull();
    assertThat(qualifiedNameOf(post.annotations(), "Getter")).isNull();
  }

  @Test
  @DisplayName("Known annotations on type, field, constructor, method and parameter get qualified")
  void qualifiesKnownAnnotationsEverywhere() {
    AnalysisContext context = AnalysisTestSupport.contextOf(projectRoot, WILDCARD_SOURCE);

    context.qualifyAnnotations(KNOWN);

    TypeModel post = context.type("com.example.domain.Post");
    assertThat(qualifiedNameOf(post.annotations(), "Entity"))
        .isEqualTo("jakarta.persistence.Entity");
    assertThat(qualifiedNameOf(post.annotations(), "Getter")).isEqualTo("lombok.Getter");
    assertThat(qualifiedNameOf(post.fields().get(0).annotations(), "Id"))
        .isEqualTo("jakarta.persistence.Id");
    assertThat(qualifiedNameOf(post.fields().get(0).annotations(), "Column"))
        .isEqualTo("jakarta.persistence.Column");
    assertThat(qualifiedNameOf(post.constructors().get(0).annotations(), "Builder"))
        .isEqualTo("lombok.Builder");
    assertThat(qualifiedNameOf(post.methods().get(0).annotations(), "GetMapping"))
        .isEqualTo("org.springframework.web.bind.annotation.GetMapping");
    assertThat(
            qualifiedNameOf(
                post.methods().get(0).parameters().get(0).annotations(), "PathVariable"))
        .isEqualTo("org.springframework.web.bind.annotation.PathVariable");
  }

  @Test
  @DisplayName("Unknown annotations stay null and attributes are preserved")
  void leavesUnknownAnnotationsAndKeepsAttributes() {
    AnalysisContext context = AnalysisTestSupport.contextOf(projectRoot, WILDCARD_SOURCE);

    context.qualifyAnnotations(KNOWN);

    TypeModel post = context.type("com.example.domain.Post");
    assertThat(qualifiedNameOf(post.annotations(), "Unknown")).isNull();
    assertThat(
            qualifiedNameOf(
                post.constructors().get(0).parameters().get(0).annotations(), "NotKnown"))
        .isNull();
    assertThat(post.fields().get(0).annotations())
        .filteredOn(a -> a.name().equals("Column"))
        .singleElement()
        .satisfies(a -> assertThat(a.attributes()).containsEntry("name", "\"post_id\""));
  }

  @Test
  @DisplayName("javax wildcard import resolves to the javax name, not jakarta")
  void choosesTheImportedPackage() {
    AnalysisContext context =
        AnalysisTestSupport.contextOf(
            projectRoot,
            """
            package com.example.legacy;
            import javax.persistence.*;
            @Entity public class Student {}
            """);

    context.qualifyAnnotations(KNOWN);

    assertThat(qualifiedNameOf(context.type("com.example.legacy.Student").annotations(), "Entity"))
        .isEqualTo("javax.persistence.Entity");
  }

  @Test
  @DisplayName("An annotation already resolved to a project type is not overwritten")
  void keepsProjectLocalResolution() {
    AnalysisContext context =
        AnalysisTestSupport.contextOf(
            projectRoot,
            """
            package com.example.marker;
            public @interface Entity {}
            """,
            """
            package com.example.app;
            import com.example.marker.Entity;
            import jakarta.persistence.*;
            @Entity public class NotJpa {}
            """);

    context.qualifyAnnotations(KNOWN);

    assertThat(qualifiedNameOf(context.type("com.example.app.NotJpa").annotations(), "Entity"))
        .isEqualTo("com.example.marker.Entity");
  }

  @Test
  @DisplayName("Parameter annotations of endpoints and @Bean methods get qualified too")
  void qualifiesEndpointAndBeanParameters() {
    AnalysisContext context = AnalysisTestSupport.contextOf(projectRoot, WILDCARD_SOURCE);
    AnnotationModel unresolvedPathVariable = new AnnotationModel("PathVariable", null, Map.of());
    AnnotationModel unresolvedOther = new AnnotationModel("NotKnown", null, Map.of());
    List<ParameterModel> parameters =
        List.of(
            new ParameterModel(
                "id",
                new TypeRef("Long", "java.lang.Long", List.of()),
                List.of(unresolvedPathVariable, unresolvedOther)));
    context.addEndpoint(
        new EndpointModel(
            "com.example.domain.Post",
            "get",
            List.of("GET"),
            List.of("/{id}"),
            true,
            null,
            null,
            null,
            parameters,
            true,
            List.of()));
    context.addBeanDefinition(
        new BeanDefinitionModel("com.example.domain.Post", "bean", List.of(), null, parameters));

    context.qualifyAnnotations(KNOWN);

    ProjectModel model = context.toProjectModel();
    List<AnnotationModel> endpointAnnotations =
        model.endpoints().get(0).parameters().get(0).annotations();
    assertThat(qualifiedNameOf(endpointAnnotations, "PathVariable"))
        .isEqualTo("org.springframework.web.bind.annotation.PathVariable");
    assertThat(qualifiedNameOf(endpointAnnotations, "NotKnown")).isNull();
    assertThat(
            qualifiedNameOf(
                model.beanDefinitions().get(0).parameters().get(0).annotations(), "PathVariable"))
        .isEqualTo("org.springframework.web.bind.annotation.PathVariable");
  }
}
