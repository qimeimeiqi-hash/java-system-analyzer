package com.example.javaanalyzer.ir;

import java.util.List;

/**
 * An association between entities (FR-07).
 *
 * @param targetType related entity (element type for collections, or {@code targetEntity})
 * @param collection whether the attribute is a collection
 * @param mappedBy {@code mappedBy} of the inverse side, or null on the owning side
 * @param optional {@code optional} as written, or null
 * @param fetch {@code fetch} as written, or null
 * @param cascade {@code cascade} values as written
 * @param joinTable null when not specified
 */
public record RelationModel(
    String fieldName,
    RelationKind kind,
    TypeRef targetType,
    boolean collection,
    String mappedBy,
    Boolean optional,
    String fetch,
    List<String> cascade,
    List<JoinColumnModel> joinColumns,
    JoinTableModel joinTable) {

  public RelationModel {
    cascade = List.copyOf(cascade);
    joinColumns = List.copyOf(joinColumns);
  }

  public boolean owningSide() {
    return mappedBy == null;
  }
}
