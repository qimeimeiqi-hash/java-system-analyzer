package com.example.javaanalyzer.generation.diagram;

import com.example.javaanalyzer.generation.Diagram;
import com.example.javaanalyzer.generation.Labels;
import com.example.javaanalyzer.ir.ColumnModel;
import com.example.javaanalyzer.ir.EntityKind;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.JoinColumnModel;
import com.example.javaanalyzer.ir.RelationModel;
import com.example.javaanalyzer.ir.TypeRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the ER diagram (FR-07, SCR-05) as a Mermaid {@code erDiagram}.
 *
 * <ul>
 *   <li>Only {@code ENTITY} types are drawn. Mapped superclasses and embeddables are shown in the
 *       tables only, and inherited columns are not copied into subclasses (screen-design SCR-05).
 *       Instead, the box title names the direct superclass as written, so an entity whose columns
 *       are all inherited does not look empty.
 *   <li>A relation with {@code mappedBy} is paired with the field of that name in the target entity
 *       when that field points back; the pair is drawn once from its owning side. Anything that
 *       cannot be paired is drawn as its own line, never guessed.
 *   <li>Multiplicity: the "one" end of a ManyToOne or OneToOne is "exactly one" only when {@code
 *       optional = false}, otherwise "zero or one" (the JPA default). "Many" ends are "zero or
 *       more" because the minimum is not known from the source.
 *   <li>A target that is not a drawn entity is still drawn, as a node marked outside the entities.
 *   <li>A line label ends with the join table or join column names written on the owning side.
 * </ul>
 */
public final class ErDiagramGenerator {

  public static final String DIAGRAM_ID = "er";

  private ErDiagramGenerator() {}

  /** Empty when the project has no {@code ENTITY}. */
  public static Optional<Diagram> diagram(List<EntityModel> entities) {
    List<EntityModel> drawn = drawnEntities(entities);
    if (drawn.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(build(drawn, Set.of(), DIAGRAM_ID, "ER図"));
  }

  /** Number of entities the ER diagram draws ({@code ENTITY} only). */
  public static int entityCount(List<EntityModel> entities) {
    return drawnEntities(entities).size();
  }

  /**
   * One ER diagram per package, for documents of projects whose single ER diagram is too large to
   * read on a page (screen-design SCR-11 "大きな図"). An entity of another package that a relation
   * points to is drawn as an outside node, so no relation is lost.
   *
   * @param rootPackage removed from the package names in the titles
   */
  public static List<Diagram> diagramsPerPackage(List<EntityModel> entities, String rootPackage) {
    List<EntityModel> drawn = drawnEntities(entities);
    Map<String, List<EntityModel>> byPackage = new java.util.TreeMap<>();
    for (EntityModel entity : drawn) {
      byPackage
          .computeIfAbsent(Labels.packageOf(entity.typeName()), unused -> new ArrayList<>())
          .add(entity);
    }
    Set<String> allNames = new java.util.HashSet<>();
    drawn.forEach(entity -> allNames.add(entity.typeName()));
    List<Diagram> diagrams = new ArrayList<>();
    for (Map.Entry<String, List<EntityModel>> entry : byPackage.entrySet()) {
      String packageName = entry.getKey();
      String relative =
          rootPackage != null && packageName.startsWith(rootPackage + ".")
              ? packageName.substring(rootPackage.length() + 1)
              : Labels.packageName(packageName);
      diagrams.add(
          build(
              entry.getValue(),
              allNames,
              DIAGRAM_ID + "-" + (diagrams.size() + 1),
              "ER図（" + relative + "）"));
    }
    return diagrams;
  }

  private static List<EntityModel> drawnEntities(List<EntityModel> entities) {
    return entities.stream()
        .filter(entity -> entity.kind() == EntityKind.ENTITY)
        .sorted(Comparator.comparing(EntityModel::typeName))
        .toList();
  }

  /**
   * @param otherEntities entities drawn in other diagrams; a target among them is labeled as being
   *     in another package rather than outside the entities
   */
  private static Diagram build(
      List<EntityModel> drawn, Set<String> otherEntities, String id, String title) {
    Map<String, String> ids = new LinkedHashMap<>();
    Map<String, EntityModel> byName = new LinkedHashMap<>();
    for (EntityModel entity : drawn) {
      ids.put(entity.typeName(), "e" + (ids.size() + 1));
      byName.put(entity.typeName(), entity);
    }

    StringBuilder code = new StringBuilder("erDiagram\n");
    for (EntityModel entity : drawn) {
      appendEntity(code, ids.get(entity.typeName()), entity);
    }

    Map<String, String> outsideIds = new LinkedHashMap<>();
    StringBuilder outsideNodes = new StringBuilder();
    StringBuilder lines = new StringBuilder();
    // Identity, not equals: two entities may declare relations with equal values
    Set<RelationModel> drawnRelations = Collections.newSetFromMap(new IdentityHashMap<>());
    for (EntityModel entity : drawn) {
      for (RelationModel relation : entity.relations()) {
        if (drawnRelations.contains(relation)) {
          continue;
        }
        drawnRelations.add(relation);
        Optional<RelationModel> partner = partner(entity, relation, byName);
        partner.ifPresent(drawnRelations::add);
        String targetId =
            targetId(relation.targetType(), ids, outsideIds, outsideNodes, otherEntities);
        if (partner.isEmpty()) {
          appendLine(
              lines, ids.get(entity.typeName()), relation, targetId, relation.fieldName(), null);
        } else if (relation.owningSide() || !partner.get().owningSide()) {
          appendLine(
              lines,
              ids.get(entity.typeName()),
              relation,
              targetId,
              relation.fieldName(),
              partner.get().fieldName());
        } else {
          appendLine(
              lines,
              targetId,
              partner.get(),
              ids.get(entity.typeName()),
              partner.get().fieldName(),
              relation.fieldName());
        }
      }
    }
    code.append(outsideNodes).append(lines);
    return new Diagram(id, title, code.toString());
  }

  private static void appendEntity(StringBuilder code, String id, EntityModel entity) {
    String table = entity.tableName() == null ? "テーブル名未指定" : entity.tableName();
    String superclass = entity.superType() == null ? "" : "継承：" + entity.superType().text();
    code.append("  ")
        .append(id)
        .append('[')
        .append(
            MermaidText.quoted(
                Labels.simpleName(entity.typeName()) + "（" + table + "）" + superclass))
        .append(']');
    if (entity.columns().isEmpty()) {
      code.append('\n');
      return;
    }
    code.append(" {\n");
    for (ColumnModel column : entity.columns()) {
      code.append("    ")
          .append(MermaidText.word(column.type().text()))
          .append(' ')
          .append(MermaidText.word(column.fieldName()));
      if (column.id()) {
        code.append(" PK");
      }
      List<String> comments = new ArrayList<>();
      if (MermaidText.losesCharacters(column.type().text())) {
        comments.add("型: " + column.type().text());
      }
      if (MermaidText.losesCharacters(column.fieldName())) {
        comments.add("フィールド: " + column.fieldName());
      }
      if (column.embedded()) {
        comments.add("埋め込み");
      }
      if (column.enumType() != null) {
        comments.add("Enum: " + column.enumType());
      }
      if (!comments.isEmpty()) {
        code.append(' ').append(MermaidText.quoted(String.join(", ", comments)));
      }
      code.append('\n');
    }
    code.append("  }\n");
  }

  /** The relation of the target entity that forms a bidirectional pair with this one. */
  private static Optional<RelationModel> partner(
      EntityModel entity, RelationModel relation, Map<String, EntityModel> byName) {
    EntityModel target = byName.get(relation.targetType().qualifiedName());
    if (target == null) {
      return Optional.empty();
    }
    for (RelationModel candidate : target.relations()) {
      if (candidate == relation
          || !entity.typeName().equals(candidate.targetType().qualifiedName())) {
        continue;
      }
      boolean thisIsInverse =
          relation.mappedBy() != null
              && relation.mappedBy().equals(candidate.fieldName())
              && candidate.owningSide();
      boolean candidateIsInverse =
          candidate.mappedBy() != null
              && candidate.mappedBy().equals(relation.fieldName())
              && relation.owningSide();
      if (thisIsInverse || candidateIsInverse) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  private static String targetId(
      TypeRef target,
      Map<String, String> ids,
      Map<String, String> outsideIds,
      StringBuilder outsideNodes,
      Set<String> otherEntities) {
    String key = target.qualifiedName() != null ? target.qualifiedName() : target.text();
    String place = otherEntities.contains(key) ? "（他パッケージ）" : "（エンティティ外）";
    String id = ids.get(key);
    if (id != null) {
      return id;
    }
    return outsideIds.computeIfAbsent(
        key,
        unused -> {
          String newId = "x" + (outsideIds.size() + 1);
          outsideNodes
              .append("  ")
              .append(newId)
              .append('[')
              .append(MermaidText.quoted(target.text() + place))
              .append("]\n");
          return newId;
        });
  }

  /**
   * Draws {@code relation}, declared in the entity {@code sourceId} and pointing to {@code
   * targetId}. The label names the field of the left entity first.
   *
   * @param partnerField field of the other side of a pair; null for a single relation
   */
  private static void appendLine(
      StringBuilder lines,
      String sourceId,
      RelationModel relation,
      String targetId,
      String sourceField,
      String partnerField) {
    String one = Boolean.FALSE.equals(relation.optional()) ? "||" : "|o";
    String left;
    String right;
    String marker;
    boolean sourceOnLeft;
    switch (relation.kind()) {
      case MANY_TO_ONE -> {
        sourceOnLeft = false;
        marker = one + "--o{";
      }
      case ONE_TO_MANY -> {
        sourceOnLeft = true;
        marker = "|o--o{";
      }
      case MANY_TO_MANY -> {
        sourceOnLeft = true;
        marker = "}o--o{";
      }
      case ONE_TO_ONE -> {
        sourceOnLeft = true;
        marker = "|o--" + (Boolean.FALSE.equals(relation.optional()) ? "||" : "o|");
      }
      default -> throw new IllegalStateException(relation.kind().toString());
    }
    left = sourceOnLeft ? sourceId : targetId;
    right = sourceOnLeft ? targetId : sourceId;
    String label;
    if (partnerField == null) {
      label = sourceField;
    } else {
      label =
          sourceOnLeft ? sourceField + " / " + partnerField : partnerField + " / " + sourceField;
    }
    label += joinSuffix(relation);
    lines
        .append("  ")
        .append(left)
        .append(' ')
        .append(marker)
        .append(' ')
        .append(right)
        .append(" : ")
        .append(MermaidText.quoted(label))
        .append('\n');
  }

  /**
   * "（customer_id）" or "（order_products）": the join table name, or else the join column names, as
   * written on the owning side. Empty when the source names none (defaults are not guessed).
   */
  private static String joinSuffix(RelationModel relation) {
    if (!relation.owningSide()) {
      return "";
    }
    if (relation.joinTable() != null && relation.joinTable().name() != null) {
      return "（" + relation.joinTable().name() + "）";
    }
    List<String> columns =
        relation.joinColumns().stream()
            .map(JoinColumnModel::name)
            .filter(java.util.Objects::nonNull)
            .toList();
    return columns.isEmpty() ? "" : "（" + String.join(", ", columns) + "）";
  }
}
