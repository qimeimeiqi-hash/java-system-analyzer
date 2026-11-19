package com.example.javaanalyzer.ir;

import java.util.List;

/**
 * Root of the intermediate representation: everything the generation layer may use.
 *
 * @param rootPackage longest common package of all sources, or empty string
 * @param mappers MyBatis mappers in package order, followed by those matching no interface
 * @param views screen templates (FR-17), sorted by path
 * @param dependencies one entry per injection target
 * @param calls method calls on injected fields, in package order
 * @param packages all packages declaring at least one type, sorted by name
 */
public record ProjectModel(
    String rootPackage,
    List<TypeModel> types,
    List<ComponentModel> components,
    List<EndpointModel> endpoints,
    List<EntityModel> entities,
    List<RepositoryModel> repositories,
    List<BeanDefinitionModel> beanDefinitions,
    List<ConfigurationPropertiesModel> configurationProperties,
    List<MapperModel> mappers,
    List<ViewTemplateModel> views,
    List<DependencyModel> dependencies,
    List<CallModel> calls,
    List<PackageModel> packages,
    List<AnalysisIssue> issues) {

  public ProjectModel {
    types = List.copyOf(types);
    components = List.copyOf(components);
    endpoints = List.copyOf(endpoints);
    entities = List.copyOf(entities);
    repositories = List.copyOf(repositories);
    beanDefinitions = List.copyOf(beanDefinitions);
    configurationProperties = List.copyOf(configurationProperties);
    mappers = List.copyOf(mappers);
    views = List.copyOf(views);
    dependencies = List.copyOf(dependencies);
    calls = List.copyOf(calls);
    packages = List.copyOf(packages);
    issues = List.copyOf(issues);
  }

  /** Returns a copy with the given calls. */
  public ProjectModel withCalls(List<CallModel> newCalls) {
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
        newCalls,
        packages,
        issues);
  }
}
