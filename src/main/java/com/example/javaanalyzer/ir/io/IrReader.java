package com.example.javaanalyzer.ir.io;

import com.example.javaanalyzer.ir.AnalysisIssue;
import com.example.javaanalyzer.ir.BeanDefinitionModel;
import com.example.javaanalyzer.ir.CallModel;
import com.example.javaanalyzer.ir.ComponentModel;
import com.example.javaanalyzer.ir.ConfigurationPropertiesModel;
import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.MapperModel;
import com.example.javaanalyzer.ir.PackageModel;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.RepositoryModel;
import com.example.javaanalyzer.ir.TypeModel;
import com.example.javaanalyzer.ir.io.IrJson.PackageContent;
import com.example.javaanalyzer.ir.io.IrJson.PackageIndexEntry;
import com.example.javaanalyzer.ir.io.IrJson.ProjectIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;

/** Loads the {@link ProjectModel} saved by {@link IrWriter}. */
public final class IrReader {

  private IrReader() {}

  /**
   * @throws IrStorageException when the files are missing, malformed or of another schema version
   */
  public static ProjectModel read(Path irDir) {
    try {
      Path indexFile = irDir.resolve(IrJson.INDEX_FILE_NAME);
      // Check the version alone first: files of another version may not fit ProjectIndex at all
      int version = readJson(indexFile, IrJson.VersionProbe.class).schemaVersion();
      if (version != IrJson.SCHEMA_VERSION) {
        throw new IrSchemaVersionException(version, IrJson.SCHEMA_VERSION);
      }
      ProjectIndex index = readJson(indexFile, ProjectIndex.class);

      List<PackageModel> packages = new ArrayList<>();
      List<TypeModel> types = new ArrayList<>();
      List<ComponentModel> components = new ArrayList<>();
      List<EndpointModel> endpoints = new ArrayList<>();
      List<EntityModel> entities = new ArrayList<>();
      List<RepositoryModel> repositories = new ArrayList<>();
      List<BeanDefinitionModel> beanDefinitions = new ArrayList<>();
      List<ConfigurationPropertiesModel> configurationProperties = new ArrayList<>();
      List<MapperModel> mappers = new ArrayList<>();
      List<CallModel> calls = new ArrayList<>();
      for (PackageIndexEntry entry : index.packages()) {
        PackageContent content = readJson(irDir.resolve(entry.file()), PackageContent.class);
        packages.add(new PackageModel(entry.name(), entry.typeNames(), entry.dependsOn()));
        types.addAll(content.types());
        components.addAll(content.components());
        endpoints.addAll(content.endpoints());
        entities.addAll(content.entities());
        repositories.addAll(content.repositories());
        beanDefinitions.addAll(content.beanDefinitions());
        configurationProperties.addAll(content.configurationProperties());
        mappers.addAll(content.mappers());
        calls.addAll(content.calls());
      }
      mappers.addAll(index.unmatchedMappers());
      List<AnalysisIssue> issues = index.issues();
      return new ProjectModel(
          index.rootPackage(),
          types,
          components,
          endpoints,
          entities,
          repositories,
          beanDefinitions,
          configurationProperties,
          mappers,
          index.views(),
          index.dependencies(),
          calls,
          packages,
          issues);
    } catch (IOException | JacksonException e) {
      throw new IrStorageException("Failed to read IR from " + irDir, e);
    }
  }

  private static <T> T readJson(Path file, Class<T> type) throws IOException {
    return IrJson.MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), type);
  }
}
