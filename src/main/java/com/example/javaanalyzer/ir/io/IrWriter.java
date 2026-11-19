package com.example.javaanalyzer.ir.io;

import com.example.javaanalyzer.ir.PackageModel;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.TypeModel;
import com.example.javaanalyzer.ir.io.IrJson.PackageContent;
import com.example.javaanalyzer.ir.io.IrJson.PackageIndexEntry;
import com.example.javaanalyzer.ir.io.IrJson.ProjectIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;

/** Saves an aggregated {@link ProjectModel} as JSON files split per package. */
public final class IrWriter {

  private static final String DEFAULT_PACKAGE_FILE_STEM = "_default";
  private static final String JSON_EXTENSION = ".json";

  private IrWriter() {}

  /**
   * @param model a model including its packages; every type must belong to a listed package, and
   *     qualified type names must be unique (the analysis reports duplicates as DUPLICATE_TYPE and
   *     keeps one of them)
   * @param irDir output folder, created when missing
   */
  public static void write(ProjectModel model, Path irDir) {
    Map<String, String> packageOfType =
        model.types().stream()
            .collect(
                Collectors.toMap(
                    TypeModel::qualifiedName,
                    TypeModel::packageName,
                    (first, second) -> {
                      throw new IllegalArgumentException(
                          "Type names in the IR must be unique; the analysis keeps one of each");
                    }));
    Set<String> packageNames =
        model.packages().stream().map(PackageModel::name).collect(Collectors.toSet());
    if (!packageNames.containsAll(packageOfType.values())) {
      throw new IllegalArgumentException("The model has types outside its package list");
    }

    Set<String> usedFileNames = new HashSet<>();
    List<PackageIndexEntry> entries = new ArrayList<>();
    try {
      Files.createDirectories(irDir.resolve(IrJson.PACKAGES_DIR_NAME));
      for (PackageModel pkg : model.packages()) {
        String fileName = uniqueFileName(pkg.name(), usedFileNames);
        String relativeFile = IrJson.PACKAGES_DIR_NAME + "/" + fileName;
        writeJson(irDir.resolve(relativeFile), packageContent(model, pkg.name(), packageOfType));
        entries.add(
            new PackageIndexEntry(pkg.name(), relativeFile, pkg.typeNames(), pkg.dependsOn()));
      }
      writeJson(
          irDir.resolve(IrJson.INDEX_FILE_NAME),
          new ProjectIndex(
              IrJson.SCHEMA_VERSION,
              model.rootPackage(),
              entries,
              model.dependencies(),
              model.mappers().stream().filter(m -> m.interfaceType() == null).toList(),
              model.views(),
              model.issues()));
    } catch (IOException | JacksonException e) {
      throw new IrStorageException("Failed to write IR to " + irDir, e);
    }
  }

  private static PackageContent packageContent(
      ProjectModel model, String packageName, Map<String, String> packageOfType) {
    Function<String, Boolean> inPackage = type -> packageName.equals(packageOfType.get(type));
    return new PackageContent(
        packageName,
        model.types().stream().filter(t -> packageName.equals(t.packageName())).toList(),
        model.components().stream().filter(c -> inPackage.apply(c.typeName())).toList(),
        model.endpoints().stream().filter(e -> inPackage.apply(e.controllerType())).toList(),
        model.entities().stream().filter(e -> inPackage.apply(e.typeName())).toList(),
        model.repositories().stream().filter(r -> inPackage.apply(r.typeName())).toList(),
        model.beanDefinitions().stream().filter(b -> inPackage.apply(b.declaringType())).toList(),
        model.configurationProperties().stream()
            .filter(p -> inPackage.apply(p.typeName()))
            .toList(),
        model.mappers().stream().filter(m -> inPackage.apply(m.interfaceType())).toList(),
        model.calls().stream().filter(c -> inPackage.apply(c.fromType())).toList());
  }

  /** Compared ignoring case: {@code com.Foo} and {@code com.foo} collide on Windows and macOS. */
  private static String uniqueFileName(String packageName, Set<String> usedFileNames) {
    String stem = packageName.isEmpty() ? DEFAULT_PACKAGE_FILE_STEM : packageName;
    String fileName = stem + JSON_EXTENSION;
    for (int suffix = 2; !usedFileNames.add(fileName.toLowerCase(Locale.ROOT)); suffix++) {
      fileName = stem + "-" + suffix + JSON_EXTENSION;
    }
    return fileName;
  }

  private static void writeJson(Path file, Object value) throws IOException {
    Files.writeString(
        file,
        IrJson.MAPPER.writeValueAsString(value) + System.lineSeparator(),
        StandardCharsets.UTF_8);
  }
}
