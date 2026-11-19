package com.example.javaanalyzer.generation;

import com.example.javaanalyzer.ir.AnnotationModel;
import com.example.javaanalyzer.ir.CallModel;
import com.example.javaanalyzer.ir.ComponentModel;
import com.example.javaanalyzer.ir.ComponentRole;
import com.example.javaanalyzer.ir.DependencyModel;
import com.example.javaanalyzer.ir.DependencyResolution;
import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.EntityKind;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.FieldModel;
import com.example.javaanalyzer.ir.InjectionKind;
import com.example.javaanalyzer.ir.InjectionModel;
import com.example.javaanalyzer.ir.MemberOrigin;
import com.example.javaanalyzer.ir.MethodModel;
import com.example.javaanalyzer.ir.ParameterModel;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.RelationKind;
import com.example.javaanalyzer.ir.RepositoryModel;
import com.example.javaanalyzer.ir.TypeKind;
import com.example.javaanalyzer.ir.TypeModel;
import com.example.javaanalyzer.ir.TypeRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Builds the class page of SCR-06 (FR-09) with the Controller / Service / Repository / Entity
 * templates, plus the simple call graph of the class (FR-18). The summary is a fixed sentence made
 * of IR facts only, such as the endpoints per HTTP method, the DI callers and dependencies and the
 * related entities; nothing is inferred beyond the structure (NFR-02, NFR-06). "利用元" and "依存先"
 * count DI dependencies only, so a class used in other ways may still say "なし".
 */
public final class ClassOverviewGenerator {

  public static final List<String> CALL_NOTES =
      List.of(
          "既知の DI 依存先フィールドに対する呼び出しのみをヒューリスティックに検出したもので、オーバーロードと多態を解決しない。",
          "外部ライブラリの呼び出し、List<Foo>・Optional<Foo> のフィールドに対する呼び出し、コンストラクタ内の呼び出しは含まない。");

  /**
   * Member sections that documents leave out when empty; the class page still shows them. Sections
   * such as 依存先 stay even when empty, because "none" is information there.
   */
  public static final Set<String> MEMBER_SECTIONS =
      Set.of("フィールド", "コンストラクタ", "メソッド", "その他のメソッド", "宣言メソッド");

  /** Entity sections that documents replace with a reference to their data model chapter. */
  public static final Set<String> ENTITY_TABLE_SECTIONS = Set.of("カラム", "関連");

  private ClassOverviewGenerator() {}

  /** Empty when the IR has no such type. */
  public static Optional<ClassOverview> of(ProjectModel model, String typeName) {
    return model.types().stream()
        .filter(type -> type.qualifiedName().equals(typeName))
        .findFirst()
        .map(type -> build(model, type));
  }

  private static ClassOverview build(ProjectModel model, TypeModel type) {
    String name = type.qualifiedName();
    Optional<ComponentModel> component =
        model.components().stream().filter(c -> c.typeName().equals(name)).findFirst();
    Optional<RepositoryModel> repository =
        model.repositories().stream().filter(r -> r.typeName().equals(name)).findFirst();
    Optional<EntityModel> entity =
        model.entities().stream().filter(e -> e.typeName().equals(name)).findFirst();
    ClassTemplate template = template(component, repository, entity);
    List<DependencyModel> dependencies =
        model.dependencies().stream().filter(d -> d.fromType().equals(name)).toList();
    List<DependencyModel> dependents =
        model.dependencies().stream().filter(d -> name.equals(d.resolvedType())).toList();
    List<CallModel> calls =
        model.calls().stream()
            .filter(call -> call.fromType().equals(name))
            .sorted(Comparator.comparingInt(CallModel::line))
            .toList();
    String place = type.simpleName() + " は " + type.packageName() + " パッケージの";

    List<Section> sections = new ArrayList<>();
    sections.add(Section.ofKeyValues("基本情報", basicInformation(model, type)));
    String summary;
    String heading;
    String methodsTitle = "メソッド";
    List<MethodModel> listedMethods = type.methods();
    switch (template) {
      case CONTROLLER -> {
        List<EndpointModel> endpoints =
            model.endpoints().stream().filter(e -> e.controllerType().equals(name)).toList();
        heading = "Controller";
        summary =
            place
                + kindWord(heading)
                + "です。エンドポイント "
                + endpoints.size()
                + " 件"
                + httpMethodCounts(endpoints)
                + "を提供する。"
                + dependencySentence(dependencies);
        sections.add(Section.ofTable("エンドポイント", endpointTable(endpoints)));
        sections.add(Section.ofTable("依存先", dependencyTable(type, component, dependencies)));
      }
      case SERVICE -> {
        List<MethodModel> publicMethods =
            type.methods().stream().filter(method -> isPublic(type, method)).toList();
        heading = "Service";
        summary =
            place
                + kindWord(heading)
                + "です。公開メソッド "
                + publicMethods.size()
                + " 件。"
                + dependentSentence(dependents)
                + dependencySentence(dependencies);
        sections.add(Section.ofTable("公開メソッド", methodTable(publicMethods)));
        sections.add(Section.ofTable("依存先", dependencyTable(type, component, dependencies)));
        sections.add(Section.ofTable("被依存", dependentTable(dependents)));
        methodsTitle = "その他のメソッド";
        listedMethods = type.methods().stream().filter(method -> !isPublic(type, method)).toList();
      }
      case REPOSITORY -> {
        heading = "Repository";
        methodsTitle = "宣言メソッド";
        String target =
            repository
                .filter(r -> r.entityType() != null)
                .map(
                    r ->
                        "エンティティ "
                            + r.entityType().text()
                            + (r.idType() == null ? "" : "（ID 型 " + r.idType().text() + "）")
                            + "を扱う。")
                .orElse("");
        summary = place + kindWord(heading) + "です。" + target + dependentSentence(dependents);
        repository.ifPresent(
            r ->
                sections.add(
                    Section.ofKeyValues(
                        "リポジトリ",
                        List.of(
                            new KeyValue("対象エンティティ", Cell.type(r.entityType())),
                            new KeyValue("ID型", Cell.type(r.idType())),
                            new KeyValue("基底型", Cell.type(r.baseType()))))));
        sections.add(Section.ofTable("被依存", dependentTable(dependents)));
      }
      case ENTITY -> {
        EntityModel entityModel = entity.orElseThrow();
        heading =
            entityModel.kind() == EntityKind.ENTITY
                ? "Entity"
                : Labels.entityKind(entityModel.kind());
        String table =
            entityModel.kind() == EntityKind.ENTITY
                ? "（テーブル：" + Labels.orUnspecified(entityModel.tableName()) + "）"
                : "";
        summary =
            place
                + kindWord(Labels.entityKind(entityModel.kind()))
                + "です"
                + table
                + "。カラム "
                + entityModel.columns().size()
                + " 件。関連："
                + names(
                    entityModel.relations().stream()
                        .map(
                            relation ->
                                typeName(relation.targetType())
                                    + "（"
                                    + multiplicity(relation.kind())
                                    + "）")
                        .toList())
                + "。";
        sections.add(
            Section.ofKeyValues(
                "テーブル",
                List.of(
                    KeyValue.of("種類", Labels.entityKind(entityModel.kind())),
                    KeyValue.of("エンティティ名", Labels.orUnspecified(entityModel.entityName())),
                    KeyValue.of("テーブル名", Labels.orUnspecified(entityModel.tableName())),
                    KeyValue.of("スキーマ", Labels.orUnspecified(entityModel.schema())))));
        sections.add(Section.ofTable("カラム", EntityTables.columns(entityModel)));
        sections.add(Section.ofTable("関連", EntityTables.relations(entityModel)));
        sections.add(Section.ofTable("対応するリポジトリ", repositoriesOf(model, name)));
      }
      default -> {
        if (component.isPresent()) {
          heading = Labels.role(component.get().role());
          summary =
              place
                  + kindWord(heading)
                  + "です。"
                  + dependentSentence(dependents)
                  + dependencySentence(dependencies);
          sections.add(Section.ofTable("依存先", dependencyTable(type, component, dependencies)));
          sections.add(Section.ofTable("被依存", dependentTable(dependents)));
        } else {
          heading = Labels.typeKind(type.kind());
          summary = place + kindWord(heading) + "です。";
        }
      }
    }
    if (component.isPresent() || !calls.isEmpty()) {
      sections.add(
          new Section("呼び出し関係（簡易）", List.of(), callTable(calls), Section.NOT_DETECTED, CALL_NOTES));
    }
    sections.add(Section.ofTable("フィールド", fieldTable(type.fields())));
    sections.add(Section.ofTable("コンストラクタ", constructorTable(type.constructors())));
    sections.add(Section.ofTable(methodsTitle, methodTable(listedMethods)));
    return new ClassOverview(
        name, type.simpleName() + "（" + heading + "）", template, summary, sections);
  }

  private static ClassTemplate template(
      Optional<ComponentModel> component,
      Optional<RepositoryModel> repository,
      Optional<EntityModel> entity) {
    ComponentRole role = component.map(ComponentModel::role).orElse(null);
    if (role == ComponentRole.CONTROLLER) {
      return ClassTemplate.CONTROLLER;
    }
    if (role == ComponentRole.SERVICE) {
      return ClassTemplate.SERVICE;
    }
    if (role == ComponentRole.REPOSITORY || repository.isPresent()) {
      return ClassTemplate.REPOSITORY;
    }
    if (entity.isPresent()) {
      return ClassTemplate.ENTITY;
    }
    return ClassTemplate.COMMON;
  }

  private static List<KeyValue> basicInformation(ProjectModel model, TypeModel type) {
    return List.of(
        KeyValue.of("完全修飾名", type.qualifiedName()),
        KeyValue.of("種類", Labels.typeKind(type.kind())),
        KeyValue.of("役割", ClassListGenerator.role(model, type.qualifiedName()).orElse(Labels.DASH)),
        KeyValue.of("パッケージ", type.packageName()),
        KeyValue.of("ソースファイル", Labels.orDash(type.sourceFile())),
        KeyValue.of("修飾子", Labels.join(type.modifiers())),
        KeyValue.of("アノテーション", annotations(type.annotations())),
        new KeyValue("親クラス", Cell.type(type.superClass())),
        KeyValue.of("インタフェース", Labels.join(type.interfaces().stream().map(TypeRef::text).toList())),
        KeyValue.of("Javadoc", type.javadoc() == null ? Labels.DASH : type.javadoc().strip()),
        KeyValue.of("コメント", type.comment() == null ? Labels.DASH : type.comment().strip()));
  }

  private static Table endpointTable(List<EndpointModel> endpoints) {
    List<List<Cell>> rows = new ArrayList<>();
    for (EndpointModel endpoint : endpoints) {
      List<String> paths = endpoint.paths().isEmpty() ? List.of(Labels.DASH) : endpoint.paths();
      for (String path : paths) {
        rows.add(
            List.of(
                Cell.of(endpoint.methodName()),
                Cell.of(
                    endpoint.httpMethods().isEmpty()
                        ? ApiListGenerator.ALL_METHODS
                        : String.join(", ", endpoint.httpMethods())),
                Cell.of(
                    endpoint.pathsResolved() || path.equals(Labels.DASH)
                        ? path
                        : path + Labels.UNRESOLVED_SUFFIX),
                Cell.type(endpoint.requestBodyType()),
                Cell.type(endpoint.responseType())));
      }
    }
    return new Table(List.of("Method", "HTTP", "Path", "Request DTO", "Response DTO"), rows);
  }

  private static Table dependencyTable(
      TypeModel type, Optional<ComponentModel> component, List<DependencyModel> dependencies) {
    List<List<Cell>> rows = new ArrayList<>();
    for (DependencyModel dependency : dependencies) {
      rows.add(
          List.of(
              Cell.of(dependency.memberName()),
              Cell.type(dependency.declaredType()),
              Cell.typeName(dependency.resolvedType()),
              Cell.of(Labels.resolution(dependency.resolution())),
              Cell.of(Labels.injectionKind(dependency.injectionKind())),
              Cell.of(origin(type, component, dependency))));
    }
    return new Table(List.of("メンバー", "宣言型", "解決先", "解決方法", "注入方式", "由来"), rows);
  }

  /** Lombok when the injection point is generated, e.g. by {@code @RequiredArgsConstructor}. */
  private static String origin(
      TypeModel type, Optional<ComponentModel> component, DependencyModel dependency) {
    Optional<InjectionModel> injection =
        component.stream()
            .flatMap(c -> c.injections().stream())
            .filter(
                i ->
                    i.memberName().equals(dependency.memberName())
                        && i.kind() == dependency.injectionKind())
            .findFirst();
    if (injection.isEmpty()) {
      return Labels.DASH;
    }
    if (injection.get().origin() != MemberOrigin.LOMBOK) {
      return Labels.origin(MemberOrigin.SOURCE, null);
    }
    List<MethodModel> candidates =
        injection.get().kind() == InjectionKind.CONSTRUCTOR
            ? type.constructors()
            : type.methods().stream()
                .filter(m -> m.name().equalsIgnoreCase("set" + dependency.memberName()))
                .toList();
    String generatedBy =
        candidates.stream()
            .filter(m -> m.origin() == MemberOrigin.LOMBOK)
            .map(MethodModel::generatedBy)
            .findFirst()
            .orElse(null);
    return Labels.origin(MemberOrigin.LOMBOK, generatedBy);
  }

  private static Table dependentTable(List<DependencyModel> dependents) {
    return new Table(
        List.of("依存元", "メンバー"),
        dependents.stream()
            .sorted(Comparator.comparing(DependencyModel::fromType))
            .map(d -> List.of(Cell.typeName(d.fromType()), Cell.of(d.memberName())))
            .toList());
  }

  private static Table callTable(List<CallModel> calls) {
    List<List<Cell>> rows = new ArrayList<>();
    for (CallModel call : calls) {
      Cell callee =
          call.targetType() != null
              ? new Cell(
                  Labels.simpleName(call.targetType()) + "." + call.methodName(), call.targetType())
              : Cell.of(call.methodName() + "（" + Labels.resolution(call.resolution()) + "）");
      rows.add(
          List.of(
              Cell.of(call.fromMethod() + "(" + String.join(", ", call.fromParameterTypes()) + ")"),
              new Cell(
                  call.fieldName() + ": " + call.declaredType().text(),
                  call.declaredType().qualifiedName()),
              callee,
              Cell.of(call.methodReference() ? "メソッド参照" : Labels.orDash(call.argumentCount())),
              Cell.of(String.valueOf(call.line()))));
    }
    return new Table(List.of("呼び出し元メソッド", "依存先フィールド", "呼び出し先", "実引数の数", "行"), rows);
  }

  private static Table fieldTable(List<FieldModel> fields) {
    return new Table(
        List.of("名前", "型", "修飾子", "アノテーション"),
        fields.stream()
            .map(
                field ->
                    List.of(
                        Cell.of(field.name()),
                        Cell.type(field.type()),
                        Cell.of(Labels.join(field.modifiers())),
                        Cell.of(annotations(field.annotations()))))
            .toList());
  }

  private static Table constructorTable(List<MethodModel> constructors) {
    return new Table(
        List.of("引数", "修飾子", "由来"),
        constructors.stream()
            .map(
                constructor ->
                    List.of(
                        Cell.of(parameters(constructor.parameters())),
                        Cell.of(Labels.join(constructor.modifiers())),
                        Cell.of(Labels.origin(constructor.origin(), constructor.generatedBy()))))
            .toList());
  }

  private static Table methodTable(List<MethodModel> methods) {
    return new Table(
        List.of("名前", "引数", "戻り値", "由来"),
        methods.stream()
            .map(
                method ->
                    List.of(
                        Cell.of(method.name()),
                        Cell.of(parameters(method.parameters())),
                        Cell.type(method.returnType()),
                        Cell.of(Labels.origin(method.origin(), method.generatedBy()))))
            .toList());
  }

  private static Table repositoriesOf(ProjectModel model, String entityName) {
    return new Table(
        List.of("リポジトリ", "ID型"),
        model.repositories().stream()
            .filter(
                r -> r.entityType() != null && entityName.equals(r.entityType().qualifiedName()))
            .sorted(Comparator.comparing(RepositoryModel::typeName))
            .map(r -> List.of(Cell.typeName(r.typeName()), Cell.type(r.idType())))
            .toList());
  }

  private static boolean isPublic(TypeModel type, MethodModel method) {
    return method.modifiers().contains("public") || type.kind() == TypeKind.INTERFACE;
  }

  private static String parameters(List<ParameterModel> parameters) {
    return Labels.join(parameters.stream().map(p -> p.type().text() + " " + p.name()).toList());
  }

  private static String annotations(List<AnnotationModel> annotations) {
    return Labels.join(annotations.stream().map(a -> "@" + a.name()).toList());
  }

  /** At most this many names are listed in a summary; the rest are counted. */
  static final int MAX_SUMMARY_NAMES = 5;

  private static final List<String> KNOWN_HTTP_METHODS =
      List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE");

  /** Known HTTP methods in their usual order, then others alphabetically, any-method last. */
  private static final Comparator<String> HTTP_METHOD_ORDER =
      Comparator.<String>comparingInt(
              method ->
                  method.equals(ApiListGenerator.ALL_METHODS)
                      ? Integer.MAX_VALUE
                      : KNOWN_HTTP_METHODS.contains(method)
                          ? KNOWN_HTTP_METHODS.indexOf(method)
                          : KNOWN_HTTP_METHODS.size())
          .thenComparing(Comparator.naturalOrder());

  /** A word written in ASCII, such as "Service", gets a space on both sides in Japanese text. */
  private static String kindWord(String word) {
    return !word.isEmpty() && word.charAt(0) < 0x80 ? " " + word + " " : word;
  }

  /** "（GET 2・POST 1・全メソッド 1）"; empty without endpoints. */
  private static String httpMethodCounts(List<EndpointModel> endpoints) {
    Map<String, Integer> counts = new TreeMap<>(HTTP_METHOD_ORDER);
    for (EndpointModel endpoint : endpoints) {
      if (endpoint.httpMethods().isEmpty()) {
        counts.merge(ApiListGenerator.ALL_METHODS, 1, Integer::sum);
      }
      endpoint.httpMethods().forEach(method -> counts.merge(method, 1, Integer::sum));
    }
    if (counts.isEmpty()) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    counts.forEach((method, count) -> parts.add(method + " " + count));
    return "（" + String.join("・", parts) + "）";
  }

  /**
   * "依存先：A、B（外部ライブラリ）。" from the DI dependencies of the class. A dependency resolved only through
   * the single implementation keeps that label (screen-design 2.3).
   */
  private static String dependencySentence(List<DependencyModel> dependencies) {
    return "依存先："
        + names(
            distinctNames(
                dependencies.stream().map(ClassOverviewGenerator::dependencyName).toList()))
        + "。";
  }

  /**
   * A name in a summary sentence: the simple name, or the qualified one when another listed class
   * has the same simple name.
   */
  private record SummaryName(String key, String simpleName, String qualifiedName) {}

  private static SummaryName dependencyName(DependencyModel dependency) {
    if (dependency.resolvedType() == null) {
      String text =
          dependency.declaredType().text() + "（" + Labels.resolution(dependency.resolution()) + "）";
      return new SummaryName(text, text, text);
    }
    String suffix =
        dependency.resolution() == DependencyResolution.SINGLE_IMPLEMENTATION
            ? "（" + Labels.resolution(dependency.resolution()) + "）"
            : "";
    return new SummaryName(
        dependency.resolvedType() + suffix,
        Labels.simpleName(dependency.resolvedType()) + suffix,
        dependency.resolvedType() + suffix);
  }

  /** "利用元：A、B。" from the DI dependencies that resolve to the class. */
  private static String dependentSentence(List<DependencyModel> dependents) {
    return "利用元："
        + names(
            distinctNames(
                dependents.stream()
                    .map(DependencyModel::fromType)
                    .sorted()
                    .map(type -> new SummaryName(type, Labels.simpleName(type), type))
                    .toList()))
        + "。";
  }

  /**
   * One name per distinct class, in order; simple names shared by different classes are replaced by
   * the qualified names so that the classes are told apart and counted separately.
   */
  private static List<String> distinctNames(List<SummaryName> names) {
    Map<String, SummaryName> byKey = new LinkedHashMap<>();
    names.forEach(name -> byKey.putIfAbsent(name.key(), name));
    Map<String, Long> simpleNameCounts =
        byKey.values().stream()
            .collect(Collectors.groupingBy(SummaryName::simpleName, Collectors.counting()));
    return byKey.values().stream()
        .map(
            name ->
                simpleNameCounts.get(name.simpleName()) > 1
                    ? name.qualifiedName()
                    : name.simpleName())
        .toList();
  }

  /** Distinct names joined with "、", at most {@link #MAX_SUMMARY_NAMES}; "なし" when empty. */
  private static String names(List<String> values) {
    List<String> distinct = values.stream().distinct().toList();
    if (distinct.isEmpty()) {
      return "なし";
    }
    String listed =
        String.join("、", distinct.subList(0, Math.min(distinct.size(), MAX_SUMMARY_NAMES)));
    return distinct.size() > MAX_SUMMARY_NAMES
        ? listed + " ほか " + (distinct.size() - MAX_SUMMARY_NAMES) + " 件"
        : listed;
  }

  private static String multiplicity(RelationKind kind) {
    return switch (kind) {
      case ONE_TO_ONE -> "1 対 1";
      case ONE_TO_MANY -> "1 対多";
      case MANY_TO_ONE -> "多対 1";
      case MANY_TO_MANY -> "多対多";
    };
  }

  private static String typeName(TypeRef type) {
    return type.qualifiedName() != null ? Labels.simpleName(type.qualifiedName()) : type.text();
  }
}
