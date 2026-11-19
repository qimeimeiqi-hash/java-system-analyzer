package com.example.javaanalyzer.ir;

import java.util.List;

/**
 * A package of the project (input for FR-12).
 *
 * @param name package name; empty string for the default package
 * @param typeNames qualified names of the types declared in it, sorted
 * @param dependsOn other packages it depends on through resolved DI dependencies, sorted
 */
public record PackageModel(String name, List<String> typeNames, List<String> dependsOn) {

  public PackageModel {
    typeNames = List.copyOf(typeNames);
    dependsOn = List.copyOf(dependsOn);
  }
}
