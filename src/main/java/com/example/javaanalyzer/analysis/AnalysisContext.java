package com.example.javaanalyzer.analysis;

import com.example.javaanalyzer.ir.AnalysisIssue;
import com.example.javaanalyzer.ir.BeanDefinitionModel;
import com.example.javaanalyzer.ir.ComponentModel;
import com.example.javaanalyzer.ir.ConfigurationPropertiesModel;
import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.MapperModel;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.RepositoryModel;
import com.example.javaanalyzer.ir.TypeModel;
import com.example.javaanalyzer.ir.ViewTemplateModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable state shared by the core and plugins during one analysis run. Plugins read parsed types
 * and add framework-specific findings; the result is frozen into an immutable {@link ProjectModel}
 * whose dependencies and packages are filled in afterwards by {@link ProjectModelAggregator}.
 */
public final class AnalysisContext {

  private final Path projectRoot;
  private final String rootPackage;
  private final List<ParsedType> parsedTypes;
  private final Map<String, TypeModel> types = new LinkedHashMap<>();
  private final List<ComponentModel> components = new ArrayList<>();
  private final List<EndpointModel> endpoints = new ArrayList<>();
  private final List<EntityModel> entities = new ArrayList<>();
  private final List<RepositoryModel> repositories = new ArrayList<>();
  private final List<BeanDefinitionModel> beanDefinitions = new ArrayList<>();
  private final List<ConfigurationPropertiesModel> configurationProperties = new ArrayList<>();
  private final List<MapperModel> mappers = new ArrayList<>();
  private final List<ViewTemplateModel> views = new ArrayList<>();
  private final List<AnalysisIssue> issues = new ArrayList<>();

  public AnalysisContext(
      Path projectRoot,
      String rootPackage,
      List<ParsedType> parsedTypes,
      List<TypeModel> typeModels) {
    this.projectRoot = projectRoot;
    this.rootPackage = rootPackage;
    this.parsedTypes = List.copyOf(parsedTypes);
    typeModels.forEach(type -> types.put(type.qualifiedName(), type));
  }

  /** The extracted project, for plugins reading files other than Java sources. */
  public Path projectRoot() {
    return projectRoot;
  }

  public String rootPackage() {
    return rootPackage;
  }

  public List<ParsedType> parsedTypes() {
    return parsedTypes;
  }

  /** The current IR of a type (including members added by earlier plugins), or null. */
  public TypeModel type(String qualifiedName) {
    return types.get(qualifiedName);
  }

  /** Replaces a type's IR, e.g. after adding Lombok-generated members. */
  public void replaceType(TypeModel type) {
    if (!types.containsKey(type.qualifiedName())) {
      throw new IllegalArgumentException("Unknown type: " + type.qualifiedName());
    }
    types.put(type.qualifiedName(), type);
  }

  /**
   * Sets the qualified name of every annotation that is unresolved in the IR but provably refers to
   * one of the given names (via an on-demand import). Plugins call this with the annotations they
   * recognize.
   */
  public void qualifyAnnotations(Collection<String> knownQualifiedNames) {
    Map<String, AnnotationQualifier> qualifiers = new LinkedHashMap<>();
    for (ParsedType parsed : parsedTypes) {
      qualifiers.put(
          parsed.qualifiedName(), new AnnotationQualifier(parsed.resolver(), knownQualifiedNames));
    }
    qualifiers.forEach(
        (typeName, qualifier) -> {
          TypeModel type = types.get(typeName);
          if (type != null) {
            types.put(typeName, qualifier.qualify(type));
          }
        });
    endpoints.replaceAll(
        endpoint -> {
          AnnotationQualifier qualifier = qualifiers.get(endpoint.controllerType());
          return qualifier == null
              ? endpoint
              : endpoint.withParameters(qualifier.parameters(endpoint.parameters()));
        });
    beanDefinitions.replaceAll(
        bean -> {
          AnnotationQualifier qualifier = qualifiers.get(bean.declaringType());
          return qualifier == null
              ? bean
              : new BeanDefinitionModel(
                  bean.declaringType(),
                  bean.methodName(),
                  bean.beanNames(),
                  bean.returnType(),
                  qualifier.parameters(bean.parameters()));
        });
  }

  public void addComponent(ComponentModel component) {
    components.add(component);
  }

  public void addEndpoint(EndpointModel endpoint) {
    endpoints.add(endpoint);
  }

  public void addEntity(EntityModel entity) {
    entities.add(entity);
  }

  public void addRepository(RepositoryModel repository) {
    repositories.add(repository);
  }

  public void addBeanDefinition(BeanDefinitionModel beanDefinition) {
    beanDefinitions.add(beanDefinition);
  }

  public void addConfigurationProperties(ConfigurationPropertiesModel properties) {
    configurationProperties.add(properties);
  }

  public void addMapper(MapperModel mapper) {
    mappers.add(mapper);
  }

  public void addView(ViewTemplateModel view) {
    views.add(view);
  }

  public void addIssue(AnalysisIssue issue) {
    issues.add(issue);
  }

  public ProjectModel toProjectModel() {
    return new ProjectModel(
        rootPackage,
        List.copyOf(types.values()),
        components,
        endpoints,
        entities,
        repositories,
        beanDefinitions,
        configurationProperties,
        mappers,
        views,
        List.of(),
        List.of(),
        List.of(),
        issues);
  }
}
