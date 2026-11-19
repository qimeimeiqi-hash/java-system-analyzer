package com.example.javaanalyzer.generation;

import com.example.javaanalyzer.ir.AnalysisIssue;
import com.example.javaanalyzer.ir.BeanDefinitionModel;
import com.example.javaanalyzer.ir.CallModel;
import com.example.javaanalyzer.ir.ComponentModel;
import com.example.javaanalyzer.ir.ComponentRole;
import com.example.javaanalyzer.ir.ConfigurationPropertiesModel;
import com.example.javaanalyzer.ir.DependencyModel;
import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.MapperModel;
import com.example.javaanalyzer.ir.PackageModel;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.RepositoryModel;
import com.example.javaanalyzer.ir.TypeKind;
import com.example.javaanalyzer.ir.TypeModel;
import com.example.javaanalyzer.ir.TypeRef;
import com.example.javaanalyzer.ir.ViewTemplateModel;
import java.util.ArrayList;
import java.util.List;

/** Builds small IR models for generator unit tests. */
final class IrFixtures {

  private IrFixtures() {}

  static ProjectBuilder project() {
    return new ProjectBuilder();
  }

  static TypeRef type(String qualifiedName) {
    String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    return new TypeRef(simpleName, qualifiedName, List.of());
  }

  static TypeRef unresolvedType(String text) {
    return new TypeRef(text, null, List.of());
  }

  static TypeModel typeModel(String qualifiedName, TypeKind kind) {
    int dot = qualifiedName.lastIndexOf('.');
    return new TypeModel(
        qualifiedName,
        qualifiedName.substring(dot + 1),
        dot < 0 ? "" : qualifiedName.substring(0, dot),
        kind,
        "src/main/java/" + qualifiedName.replace('.', '/') + ".java",
        List.of("public"),
        List.of(),
        null,
        null,
        null,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  static ComponentModel component(String typeName, ComponentRole role) {
    return new ComponentModel(typeName, role, List.of());
  }

  /** Mutable builder; every list starts empty. */
  static final class ProjectBuilder {
    private String rootPackage = "com.example";
    private final List<TypeModel> types = new ArrayList<>();
    private final List<ComponentModel> components = new ArrayList<>();
    private final List<EndpointModel> endpoints = new ArrayList<>();
    private final List<EntityModel> entities = new ArrayList<>();
    private final List<RepositoryModel> repositories = new ArrayList<>();
    private final List<BeanDefinitionModel> beanDefinitions = new ArrayList<>();
    private final List<ConfigurationPropertiesModel> configurationProperties = new ArrayList<>();
    private final List<MapperModel> mappers = new ArrayList<>();
    private final List<ViewTemplateModel> views = new ArrayList<>();
    private final List<DependencyModel> dependencies = new ArrayList<>();
    private final List<CallModel> calls = new ArrayList<>();
    private final List<PackageModel> packages = new ArrayList<>();
    private final List<AnalysisIssue> issues = new ArrayList<>();

    ProjectBuilder rootPackage(String value) {
      rootPackage = value;
      return this;
    }

    ProjectBuilder types(TypeModel... values) {
      types.addAll(List.of(values));
      return this;
    }

    ProjectBuilder components(ComponentModel... values) {
      components.addAll(List.of(values));
      return this;
    }

    ProjectBuilder endpoints(EndpointModel... values) {
      endpoints.addAll(List.of(values));
      return this;
    }

    ProjectBuilder entities(EntityModel... values) {
      entities.addAll(List.of(values));
      return this;
    }

    ProjectBuilder repositories(RepositoryModel... values) {
      repositories.addAll(List.of(values));
      return this;
    }

    ProjectBuilder beanDefinitions(BeanDefinitionModel... values) {
      beanDefinitions.addAll(List.of(values));
      return this;
    }

    ProjectBuilder configurationProperties(ConfigurationPropertiesModel... values) {
      configurationProperties.addAll(List.of(values));
      return this;
    }

    ProjectBuilder mappers(MapperModel... values) {
      mappers.addAll(List.of(values));
      return this;
    }

    ProjectBuilder views(ViewTemplateModel... values) {
      views.addAll(List.of(values));
      return this;
    }

    ProjectBuilder dependencies(DependencyModel... values) {
      dependencies.addAll(List.of(values));
      return this;
    }

    ProjectBuilder calls(CallModel... values) {
      calls.addAll(List.of(values));
      return this;
    }

    ProjectBuilder packages(PackageModel... values) {
      packages.addAll(List.of(values));
      return this;
    }

    ProjectBuilder issues(AnalysisIssue... values) {
      issues.addAll(List.of(values));
      return this;
    }

    ProjectModel build() {
      return new ProjectModel(
          rootPackage,
          types,
          components,
          endpoints,
          entities,
          repositories,
          beanDefinitions,
          configurationProperties,
          mappers,
          views,
          dependencies,
          calls,
          packages,
          issues);
    }
  }
}
