package com.example.javaanalyzer.ir.io;

import com.example.javaanalyzer.ir.AnalysisIssue;
import com.example.javaanalyzer.ir.BeanDefinitionModel;
import com.example.javaanalyzer.ir.CallModel;
import com.example.javaanalyzer.ir.ComponentModel;
import com.example.javaanalyzer.ir.ConfigurationPropertiesModel;
import com.example.javaanalyzer.ir.DependencyModel;
import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.MapperModel;
import com.example.javaanalyzer.ir.RepositoryModel;
import com.example.javaanalyzer.ir.TypeModel;
import com.example.javaanalyzer.ir.ViewTemplateModel;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * File layout and JSON format of saved IR.
 *
 * <pre>
 * project.json          ProjectIndex: schema version, package index, dependencies, views, issues
 * packages/&lt;pkg&gt;.json   PackageContent: what is declared in that package
 * </pre>
 */
final class IrJson {

  /**
   * Raise whenever an IR record changes: a field is added, removed or changes meaning. Saved IR of
   * another version is not migrated; {@link IrReader} rejects it with {@link
   * IrSchemaVersionException} and the user re-uploads the project.
   *
   * <ul>
   *   <li>1: initial format (No.10)
   *   <li>2: MyBatis, configuration, view and call graph fields (No.20-23)
   *   <li>3: issue kind DUPLICATE_TYPE; comments of fields and methods, and comments in method
   *       bodies (No.17)
   * </ul>
   */
  static final int SCHEMA_VERSION = 3;

  static final String INDEX_FILE_NAME = "project.json";
  static final String PACKAGES_DIR_NAME = "packages";

  /**
   * Indented for human reading. Map entries are sorted because the IR's immutable maps have a
   * per-JVM random iteration order, which would make identical analyses produce different files.
   */
  static final JsonMapper MAPPER =
      JsonMapper.builder()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .changeDefaultPropertyInclusion(
              inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
          .build();

  private IrJson() {}

  /** Reads only the version of project.json, whatever the rest of the file looks like. */
  record VersionProbe(int schemaVersion) {}

  /**
   * @param file path of the {@link PackageContent} file, relative to the IR folder
   */
  record PackageIndexEntry(
      String name, String file, List<String> typeNames, List<String> dependsOn) {

    PackageIndexEntry {
      typeNames = List.copyOf(typeNames);
      dependsOn = List.copyOf(dependsOn);
    }
  }

  record ProjectIndex(
      int schemaVersion,
      String rootPackage,
      List<PackageIndexEntry> packages,
      List<DependencyModel> dependencies,
      List<MapperModel> unmatchedMappers,
      List<ViewTemplateModel> views,
      List<AnalysisIssue> issues) {

    ProjectIndex {
      views = List.copyOf(views);
      unmatchedMappers = List.copyOf(unmatchedMappers);
      packages = List.copyOf(packages);
      dependencies = List.copyOf(dependencies);
      issues = List.copyOf(issues);
    }
  }

  record PackageContent(
      String packageName,
      List<TypeModel> types,
      List<ComponentModel> components,
      List<EndpointModel> endpoints,
      List<EntityModel> entities,
      List<RepositoryModel> repositories,
      List<BeanDefinitionModel> beanDefinitions,
      List<ConfigurationPropertiesModel> configurationProperties,
      List<MapperModel> mappers,
      List<CallModel> calls) {

    PackageContent {
      mappers = List.copyOf(mappers);
      calls = List.copyOf(calls);
      beanDefinitions = List.copyOf(beanDefinitions);
      configurationProperties = List.copyOf(configurationProperties);
      types = List.copyOf(types);
      components = List.copyOf(components);
      endpoints = List.copyOf(endpoints);
      entities = List.copyOf(entities);
      repositories = List.copyOf(repositories);
    }
  }
}
