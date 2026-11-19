package com.example.javaanalyzer.generation;

import com.example.javaanalyzer.ir.BeanDefinitionModel;
import com.example.javaanalyzer.ir.ComponentModel;
import com.example.javaanalyzer.ir.ComponentRole;
import com.example.javaanalyzer.ir.ConfigurationPropertiesModel;
import com.example.javaanalyzer.ir.DependencyModel;
import com.example.javaanalyzer.ir.ProjectModel;
import java.util.Comparator;
import java.util.List;

/**
 * The Configuration screen (FR-16, SCR-09). Bean contents such as a {@code SecurityFilterChain} are
 * not interpreted, and default bean names are not guessed.
 */
public final class ConfigurationReportGenerator {

  private ConfigurationReportGenerator() {}

  public static Table beans(ProjectModel model) {
    return new Table(
        List.of("定義元クラス", "Bean メソッド", "Bean 名", "戻り値型", "引数"),
        model.beanDefinitions().stream()
            .sorted(
                Comparator.comparing(BeanDefinitionModel::declaringType)
                    .thenComparing(BeanDefinitionModel::methodName))
            .map(
                bean ->
                    List.of(
                        Cell.typeName(bean.declaringType()),
                        Cell.of(bean.methodName()),
                        Cell.of(
                            bean.beanNames().isEmpty()
                                ? Labels.UNSPECIFIED
                                : String.join(", ", bean.beanNames())),
                        Cell.type(bean.returnType()),
                        Cell.of(
                            Labels.join(
                                bean.parameters().stream()
                                    .map(p -> p.type().text() + " " + p.name())
                                    .toList()))))
            .toList());
  }

  public static Table properties(ProjectModel model) {
    return new Table(
        List.of("クラス", "Bean メソッド", "prefix"),
        model.configurationProperties().stream()
            .sorted(Comparator.comparing(ConfigurationPropertiesModel::typeName))
            .map(
                properties ->
                    List.of(
                        Cell.typeName(properties.typeName()),
                        Cell.of(Labels.orDash(properties.beanMethodName())),
                        Cell.of(
                            properties.prefixResolved()
                                ? Labels.orDash(properties.prefix())
                                : properties.prefix() + Labels.UNRESOLVED_SUFFIX)))
            .toList());
  }

  /** {@code @Configuration} classes and what is injected into them. */
  public static Table configurationClasses(ProjectModel model) {
    return new Table(
        List.of("クラス", "依存先"),
        model.components().stream()
            .filter(component -> component.role() == ComponentRole.CONFIGURATION)
            .map(ComponentModel::typeName)
            .sorted()
            .map(
                typeName ->
                    List.of(
                        Cell.typeName(typeName),
                        Cell.of(
                            Labels.join(
                                model.dependencies().stream()
                                    .filter(d -> d.fromType().equals(typeName))
                                    .sorted(Comparator.comparing(DependencyModel::memberName))
                                    .map(ConfigurationReportGenerator::target)
                                    .toList()))))
            .toList());
  }

  private static String target(DependencyModel dependency) {
    if (DependencyTables.isDrawn(dependency)) {
      return Labels.simpleName(dependency.resolvedType());
    }
    return dependency.declaredType().text()
        + "（"
        + Labels.resolution(dependency.resolution())
        + "）";
  }
}
