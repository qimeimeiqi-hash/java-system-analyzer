package com.example.javaanalyzer.ir;

import java.util.List;

/** A managed component (DI target) and the dependencies injected into it. */
public record ComponentModel(String typeName, ComponentRole role, List<InjectionModel> injections) {

  public ComponentModel {
    injections = List.copyOf(injections);
  }
}
