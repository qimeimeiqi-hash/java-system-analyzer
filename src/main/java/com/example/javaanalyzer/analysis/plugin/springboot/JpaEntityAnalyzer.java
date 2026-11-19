package com.example.javaanalyzer.analysis.plugin.springboot;

import static com.example.javaanalyzer.analysis.plugin.springboot.AnnotationNames.persistence;

import com.example.javaanalyzer.analysis.AnalysisContext;
import com.example.javaanalyzer.analysis.AnnotationSupport;
import com.example.javaanalyzer.analysis.ParsedType;
import com.example.javaanalyzer.analysis.StructureExtractor;
import com.example.javaanalyzer.analysis.TypeNameResolver;
import com.example.javaanalyzer.ir.ColumnModel;
import com.example.javaanalyzer.ir.EntityKind;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.JoinColumnModel;
import com.example.javaanalyzer.ir.JoinTableModel;
import com.example.javaanalyzer.ir.RelationKind;
import com.example.javaanalyzer.ir.RelationModel;
import com.example.javaanalyzer.ir.TypeRef;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Extracts JPA entities, columns and associations (FR-03, data model first). Supports both {@code
 * jakarta.persistence} and {@code javax.persistence}. Only values written in source are recorded;
 * defaults (table names, column names, fetch types) are never derived.
 *
 * <p>Limitations: only field access is analyzed (mapping annotations on getters are ignored);
 * attribute values given as constants are not resolved (recorded as null);
 * {@code @AttributeOverride} and {@code @ElementCollection} are not modeled beyond the field's
 * annotations in the type IR.
 */
class JpaEntityAnalyzer {

  private static final String[] ENTITY = persistence("Entity");
  private static final String[] MAPPED_SUPERCLASS = persistence("MappedSuperclass");
  private static final String[] EMBEDDABLE = persistence("Embeddable");
  private static final String[] TABLE = persistence("Table");
  private static final String[] TRANSIENT = persistence("Transient");
  private static final String[] ID = persistence("Id");
  private static final String[] EMBEDDED_ID = persistence("EmbeddedId");
  private static final String[] EMBEDDED = persistence("Embedded");
  private static final String[] GENERATED_VALUE = persistence("GeneratedValue");
  private static final String[] COLUMN = persistence("Column");
  private static final String[] ENUMERATED = persistence("Enumerated");
  private static final String[] JOIN_COLUMN = persistence("JoinColumn");
  private static final String[] JOIN_COLUMNS = persistence("JoinColumns");
  private static final String[] JOIN_TABLE = persistence("JoinTable");

  private record RelationAnnotation(String[] names, RelationKind kind) {}

  private static final List<RelationAnnotation> RELATION_ANNOTATIONS =
      List.of(
          new RelationAnnotation(persistence("OneToOne"), RelationKind.ONE_TO_ONE),
          new RelationAnnotation(persistence("OneToMany"), RelationKind.ONE_TO_MANY),
          new RelationAnnotation(persistence("ManyToOne"), RelationKind.MANY_TO_ONE),
          new RelationAnnotation(persistence("ManyToMany"), RelationKind.MANY_TO_MANY));

  void analyze(AnalysisContext context) {
    for (ParsedType parsed : context.parsedTypes()) {
      TypeDeclaration<?> declaration = parsed.declaration();
      boolean persistentClass =
          (declaration instanceof ClassOrInterfaceDeclaration type && !type.isInterface())
              || declaration instanceof RecordDeclaration;
      if (persistentClass) {
        EntityKind kind = kindOf(declaration, parsed.resolver());
        if (kind != null) {
          context.addEntity(entity(parsed, declaration, kind));
        }
      }
    }
  }

  private static EntityKind kindOf(TypeDeclaration<?> declaration, TypeNameResolver resolver) {
    if (AnnotationSupport.has(declaration, resolver, ENTITY)) {
      return EntityKind.ENTITY;
    }
    if (AnnotationSupport.has(declaration, resolver, MAPPED_SUPERCLASS)) {
      return EntityKind.MAPPED_SUPERCLASS;
    }
    if (AnnotationSupport.has(declaration, resolver, EMBEDDABLE)) {
      return EntityKind.EMBEDDABLE;
    }
    return null;
  }

  /** A persistent attribute: a field variable, or a record component of an embeddable record. */
  private record Attribute(NodeWithAnnotations<?> annotated, String name, Type type) {}

  private static EntityModel entity(
      ParsedType parsed, TypeDeclaration<?> declaration, EntityKind kind) {
    TypeNameResolver resolver = parsed.resolver();
    String entityName =
        AnnotationSupport.find(declaration, resolver, ENTITY)
            .map(annotation -> stringAttribute(annotation, "name"))
            .orElse(null);
    Optional<AnnotationExpr> table = AnnotationSupport.find(declaration, resolver, TABLE);
    String tableName = table.map(annotation -> stringAttribute(annotation, "name")).orElse(null);
    String schema = table.map(annotation -> stringAttribute(annotation, "schema")).orElse(null);
    TypeRef superType =
        declaration instanceof ClassOrInterfaceDeclaration type
            ? type.getExtendedTypes()
                .getFirst()
                .map(extended -> StructureExtractor.typeRef(extended, resolver))
                .orElse(null)
            : null;

    List<ColumnModel> columns = new ArrayList<>();
    List<RelationModel> relations = new ArrayList<>();
    for (Attribute attribute : attributes(declaration, resolver)) {
      Optional<RelationAnnotation> relationAnnotation =
          RELATION_ANNOTATIONS.stream()
              .filter(
                  candidate ->
                      AnnotationSupport.has(attribute.annotated(), resolver, candidate.names()))
              .findFirst();
      if (relationAnnotation.isPresent()) {
        relations.add(relation(attribute, relationAnnotation.get(), resolver));
      } else {
        columns.add(column(attribute, resolver));
      }
    }
    return new EntityModel(
        parsed.qualifiedName(), kind, entityName, tableName, schema, superType, columns, relations);
  }

  /** Instance fields that are not transient, or the components of a record. */
  private static List<Attribute> attributes(
      TypeDeclaration<?> declaration, TypeNameResolver resolver) {
    List<Attribute> attributes = new ArrayList<>();
    if (declaration instanceof RecordDeclaration record) {
      for (Parameter component : record.getParameters()) {
        attributes.add(new Attribute(component, component.getNameAsString(), component.getType()));
      }
      return attributes;
    }
    for (FieldDeclaration field : declaration.getFields()) {
      if (field.isStatic()
          || field.hasModifier(Modifier.Keyword.TRANSIENT)
          || AnnotationSupport.has(field, resolver, TRANSIENT)) {
        continue;
      }
      for (VariableDeclarator variable : field.getVariables()) {
        attributes.add(new Attribute(field, variable.getNameAsString(), variable.getType()));
      }
    }
    return attributes;
  }

  private static ColumnModel column(Attribute attribute, TypeNameResolver resolver) {
    NodeWithAnnotations<?> field = attribute.annotated();
    Optional<AnnotationExpr> column = AnnotationSupport.find(field, resolver, COLUMN);
    boolean embeddedId = AnnotationSupport.has(field, resolver, EMBEDDED_ID);
    return new ColumnModel(
        attribute.name(),
        StructureExtractor.typeRef(attribute.type(), resolver),
        column.map(annotation -> stringAttribute(annotation, "name")).orElse(null),
        embeddedId || AnnotationSupport.has(field, resolver, ID),
        AnnotationSupport.find(field, resolver, GENERATED_VALUE)
            .map(annotation -> constantAttributeOrEmpty(annotation, "strategy"))
            .orElse(null),
        column.map(annotation -> booleanAttribute(annotation, "nullable")).orElse(null),
        column
            .flatMap(annotation -> AnnotationSupport.attribute(annotation, "length"))
            .flatMap(AnnotationSupport::intValue)
            .orElse(null),
        column.map(annotation -> booleanAttribute(annotation, "unique")).orElse(null),
        AnnotationSupport.find(field, resolver, ENUMERATED)
            .map(annotation -> constantAttributeOrEmpty(annotation, "value"))
            .orElse(null),
        embeddedId || AnnotationSupport.has(field, resolver, EMBEDDED));
  }

  private static RelationModel relation(
      Attribute attribute, RelationAnnotation relationAnnotation, TypeNameResolver resolver) {
    NodeWithAnnotations<?> field = attribute.annotated();
    AnnotationExpr annotation =
        AnnotationSupport.find(field, resolver, relationAnnotation.names()).orElseThrow();
    RelationKind kind = relationAnnotation.kind();
    boolean collection = kind == RelationKind.ONE_TO_MANY || kind == RelationKind.MANY_TO_MANY;
    TypeRef targetType =
        AnnotationSupport.attribute(annotation, "targetEntity")
            .flatMap(expression -> classLiteral(expression, resolver))
            .orElseGet(() -> declaredTarget(attribute.type(), collection, resolver));
    List<String> cascade =
        AnnotationSupport.attribute(annotation, "cascade")
            .map(
                expression ->
                    AnnotationSupport.values(expression).stream()
                        .map(AnnotationSupport::constantName)
                        .toList())
            .orElse(List.of());

    List<JoinColumnModel> joinColumns = new ArrayList<>();
    AnnotationSupport.find(field, resolver, JOIN_COLUMN)
        .ifPresent(joinColumn -> joinColumns.add(joinColumn(joinColumn)));
    AnnotationSupport.find(field, resolver, JOIN_COLUMNS)
        .flatMap(
            joinColumnsAnnotation -> AnnotationSupport.attribute(joinColumnsAnnotation, "value"))
        .ifPresent(expression -> joinColumns.addAll(joinColumnList(expression)));

    JoinTableModel joinTable =
        AnnotationSupport.find(field, resolver, JOIN_TABLE)
            .map(
                joinTableAnnotation ->
                    new JoinTableModel(
                        stringAttribute(joinTableAnnotation, "name"),
                        AnnotationSupport.attribute(joinTableAnnotation, "joinColumns")
                            .map(JpaEntityAnalyzer::joinColumnList)
                            .orElse(List.of()),
                        AnnotationSupport.attribute(joinTableAnnotation, "inverseJoinColumns")
                            .map(JpaEntityAnalyzer::joinColumnList)
                            .orElse(List.of())))
            .orElse(null);

    return new RelationModel(
        attribute.name(),
        kind,
        targetType,
        collection,
        stringAttribute(annotation, "mappedBy"),
        booleanAttribute(annotation, "optional"),
        AnnotationSupport.attribute(annotation, "fetch")
            .map(AnnotationSupport::constantName)
            .orElse(null),
        cascade,
        joinColumns,
        joinTable);
  }

  /** The class named by a {@code X.class} literal, resolved through the file's imports. */
  private static Optional<TypeRef> classLiteral(Expression expression, TypeNameResolver resolver) {
    if (expression instanceof ClassExpr classExpr) {
      Type type = classExpr.getType();
      String name =
          type instanceof ClassOrInterfaceType classType
              ? classType.getNameWithScope()
              : type.asString();
      return Optional.of(
          new TypeRef(name, resolver.scopedTo(classExpr).resolveName(name), List.of()));
    }
    return Optional.empty();
  }

  /**
   * The target from the field type: the element type for collections ({@code List<Order>}), the
   * value type for maps (the last type argument), otherwise the field type itself. A raw collection
   * keeps the field type, since its element type is not written in source.
   */
  private static TypeRef declaredTarget(Type type, boolean collection, TypeNameResolver resolver) {
    TypeRef fieldType = StructureExtractor.typeRef(type, resolver);
    if (collection && !fieldType.typeArguments().isEmpty()) {
      return fieldType.typeArguments().getLast();
    }
    return fieldType;
  }

  private static List<JoinColumnModel> joinColumnList(Expression expression) {
    return AnnotationSupport.values(expression).stream()
        .filter(AnnotationExpr.class::isInstance)
        .map(element -> joinColumn((AnnotationExpr) element))
        .toList();
  }

  private static JoinColumnModel joinColumn(AnnotationExpr annotation) {
    return new JoinColumnModel(
        stringAttribute(annotation, "name"),
        stringAttribute(annotation, "referencedColumnName"),
        booleanAttribute(annotation, "nullable"));
  }

  private static String stringAttribute(AnnotationExpr annotation, String name) {
    return AnnotationSupport.attribute(annotation, name)
        .flatMap(AnnotationSupport::stringValue)
        .orElse(null);
  }

  private static Boolean booleanAttribute(AnnotationExpr annotation, String name) {
    return AnnotationSupport.attribute(annotation, name)
        .flatMap(AnnotationSupport::booleanValue)
        .orElse(null);
  }

  /** Enum constant name as written, or an empty string when the attribute is omitted. */
  private static String constantAttributeOrEmpty(AnnotationExpr annotation, String name) {
    return AnnotationSupport.attribute(annotation, name)
        .map(AnnotationSupport::constantName)
        .orElse("");
  }
}
