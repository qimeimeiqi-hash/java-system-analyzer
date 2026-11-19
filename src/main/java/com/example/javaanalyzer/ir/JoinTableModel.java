package com.example.javaanalyzer.ir;

import java.util.List;

/** A {@code @JoinTable}; a null name means "not specified". */
public record JoinTableModel(
    String name, List<JoinColumnModel> joinColumns, List<JoinColumnModel> inverseJoinColumns) {

  public JoinTableModel {
    joinColumns = List.copyOf(joinColumns);
    inverseJoinColumns = List.copyOf(inverseJoinColumns);
  }
}
