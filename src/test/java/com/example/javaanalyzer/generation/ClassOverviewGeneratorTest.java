package com.example.javaanalyzer.generation;

import static com.example.javaanalyzer.generation.IrFixtures.project;
import static com.example.javaanalyzer.generation.IrFixtures.type;
import static com.example.javaanalyzer.generation.IrFixtures.unresolvedType;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.ir.AnnotationModel;
import com.example.javaanalyzer.ir.CallModel;
import com.example.javaanalyzer.ir.ColumnModel;
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
import com.example.javaanalyzer.ir.RelationModel;
import com.example.javaanalyzer.ir.RepositoryModel;
import com.example.javaanalyzer.ir.TypeKind;
import com.example.javaanalyzer.ir.TypeModel;
import com.example.javaanalyzer.ir.TypeRef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClassOverviewGeneratorTest {

  private static final String CONTROLLER = "com.example.shop.web.OrderController";
  private static final String SERVICE = "com.example.shop.service.OrderService";
  private static final String REPOSITORY = "com.example.shop.repository.PurchaseOrderRepository";
  private static final String ENTITY = "com.example.shop.domain.PurchaseOrder";
  private static final String UTIL = "com.example.shop.util.Strings";

  private static final TypeRef VOID = new TypeRef("void", "void", List.of());
  private static final TypeRef LONG = type("java.lang.Long");

  private static ParameterModel parameter(String name, TypeRef type) {
    return new ParameterModel(name, type, List.of());
  }

  private static MethodModel method(
      String name, TypeRef returnType, List<String> modifiers, ParameterModel... parameters) {
    return new MethodModel(
        name,
        returnType,
        List.of(parameters),
        modifiers,
        List.of(),
        null,
        null,
        List.of(),
        MemberOrigin.SOURCE,
        null);
  }

  private static TypeModel typeModel(
      String qualifiedName,
      TypeKind kind,
      String javadoc,
      List<FieldModel> fields,
      List<MethodModel> constructors,
      List<MethodModel> methods) {
    int dot = qualifiedName.lastIndexOf('.');
    return new TypeModel(
        qualifiedName,
        qualifiedName.substring(dot + 1),
        qualifiedName.substring(0, dot),
        kind,
        "src/main/java/" + qualifiedName.replace('.', '/') + ".java",
        List.of("public"),
        List.of(new AnnotationModel("Service", "org.springframework.stereotype.Service", Map.of())),
        javadoc,
        null,
        null,
        List.of(),
        List.of(),
        fields,
        constructors,
        methods);
  }

  /** OrderController -> OrderService -> PurchaseOrderRepository, as in lombok-jpa-demo. */
  private static ProjectModel shop() {
    TypeModel service =
        typeModel(
            SERVICE,
            TypeKind.CLASS,
            "Places and looks up orders.",
            List.of(
                new FieldModel(
                    "purchaseOrderRepository",
                    type(REPOSITORY),
                    List.of("private", "final"),
                    List.of(),
                    null,
                    null,
                    false)),
            List.of(
                new MethodModel(
                    "OrderService",
                    null,
                    List.of(parameter("purchaseOrderRepository", type(REPOSITORY))),
                    List.of("public"),
                    List.of(),
                    null,
                    null,
                    List.of(),
                    MemberOrigin.LOMBOK,
                    "RequiredArgsConstructor")),
            List.of(
                method("find", type(ENTITY), List.of("public"), parameter("id", LONG)),
                method("audit", VOID, List.of("private"))));
    return project()
        .types(
            service,
            typeModel(CONTROLLER, TypeKind.CLASS, null, List.of(), List.of(), List.of()),
            typeModel(
                REPOSITORY,
                TypeKind.INTERFACE,
                null,
                List.of(),
                List.of(),
                List.of(
                    method(
                        "findByCode",
                        type(ENTITY),
                        List.of(),
                        parameter("code", type("java.lang.String"))))),
            typeModel(ENTITY, TypeKind.CLASS, "An order.", List.of(), List.of(), List.of()),
            typeModel(UTIL, TypeKind.CLASS, null, List.of(), List.of(), List.of()))
        .components(
            new ComponentModel(
                CONTROLLER,
                ComponentRole.CONTROLLER,
                List.of(
                    new InjectionModel(
                        InjectionKind.CONSTRUCTOR,
                        "orderService",
                        type(SERVICE),
                        MemberOrigin.LOMBOK,
                        null,
                        type(SERVICE),
                        false))),
            new ComponentModel(
                SERVICE,
                ComponentRole.SERVICE,
                List.of(
                    new InjectionModel(
                        InjectionKind.CONSTRUCTOR,
                        "purchaseOrderRepository",
                        type(REPOSITORY),
                        MemberOrigin.LOMBOK,
                        null,
                        type(REPOSITORY),
                        false))),
            new ComponentModel(REPOSITORY, ComponentRole.REPOSITORY, List.of()))
        .repositories(
            new RepositoryModel(
                REPOSITORY,
                new TypeRef(
                    "JpaRepository<PurchaseOrder, Long>",
                    "org.springframework.data.jpa.repository.JpaRepository",
                    List.of()),
                type(ENTITY),
                LONG))
        .entities(
            new EntityModel(
                ENTITY,
                EntityKind.ENTITY,
                null,
                "purchase_orders",
                null,
                null,
                List.of(
                    new ColumnModel(
                        "code",
                        type("java.lang.String"),
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false)),
                List.of()))
        .endpoints(
            new EndpointModel(
                CONTROLLER,
                "get",
                List.of("GET"),
                List.of("/api/orders/{id}"),
                true,
                null,
                type(ENTITY),
                type(ENTITY),
                List.of(),
                true,
                List.of()))
        .dependencies(
            new DependencyModel(
                CONTROLLER,
                "orderService",
                InjectionKind.CONSTRUCTOR,
                type(SERVICE),
                SERVICE,
                DependencyResolution.PROJECT_TYPE),
            new DependencyModel(
                SERVICE,
                "purchaseOrderRepository",
                InjectionKind.CONSTRUCTOR,
                type(REPOSITORY),
                REPOSITORY,
                DependencyResolution.PROJECT_TYPE))
        .calls(
            new CallModel(
                SERVICE,
                "find",
                List.of("Long"),
                "purchaseOrderRepository",
                "findById",
                false,
                1,
                type(REPOSITORY),
                REPOSITORY,
                DependencyResolution.PROJECT_TYPE,
                22),
            new CallModel(
                SERVICE,
                "find",
                List.of("Long"),
                "notifiers",
                "notify",
                true,
                null,
                type("com.example.shop.service.Notifier"),
                null,
                DependencyResolution.AMBIGUOUS,
                23))
        .build();
  }

  private static Section section(ClassOverview overview, String title) {
    return overview.sections().stream()
        .filter(section -> section.title().equals(title))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no section " + title));
  }

  private static List<String> titles(ClassOverview overview) {
    return overview.sections().stream().map(Section::title).toList();
  }

  @Test
  @DisplayName(
      "Service template: summary, public methods, dependencies with Lombok origin, callers")
  void serviceTemplate() {
    ClassOverview overview = ClassOverviewGenerator.of(shop(), SERVICE).orElseThrow();

    assertThat(overview.template()).isEqualTo(ClassTemplate.SERVICE);
    assertThat(overview.heading()).isEqualTo("OrderService（Service）");
    assertThat(overview.summary())
        .isEqualTo(
            "OrderService は com.example.shop.service パッケージの Service です。公開メソッド 1 件。利用元：OrderController。依存先：PurchaseOrderRepository。");
    assertThat(titles(overview))
        .containsExactly(
            "基本情報", "公開メソッド", "依存先", "被依存", "呼び出し関係（簡易）", "フィールド", "コンストラクタ", "その他のメソッド");
    assertThat(section(overview, "公開メソッド").table().texts())
        .containsExactly(List.of("find", "Long id", "PurchaseOrder", "ソース"));
    assertThat(section(overview, "その他のメソッド").table().texts())
        .containsExactly(List.of("audit", "-", "void", "ソース"));
    assertThat(section(overview, "依存先").table().texts())
        .containsExactly(
            List.of(
                "purchaseOrderRepository",
                "PurchaseOrderRepository",
                "PurchaseOrderRepository",
                "直接解決",
                "コンストラクタ",
                "Lombok（@RequiredArgsConstructor）"));
    assertThat(section(overview, "被依存").table().texts())
        .containsExactly(List.of("OrderController", "orderService"));
    assertThat(section(overview, "コンストラクタ").table().texts())
        .containsExactly(
            List.of(
                "PurchaseOrderRepository purchaseOrderRepository",
                "public",
                "Lombok（@RequiredArgsConstructor）"));
  }

  @Test
  @DisplayName("Call graph rows show the caller signature, the field, the callee and notes")
  void callGraphSection() {
    Section calls = section(ClassOverviewGenerator.of(shop(), SERVICE).orElseThrow(), "呼び出し関係（簡易）");

    assertThat(calls.table().headers())
        .containsExactly("呼び出し元メソッド", "依存先フィールド", "呼び出し先", "実引数の数", "行");
    assertThat(calls.table().texts())
        .containsExactly(
            List.of(
                "find(Long)",
                "purchaseOrderRepository: PurchaseOrderRepository",
                "PurchaseOrderRepository.findById",
                "1",
                "22"),
            List.of("find(Long)", "notifiers: Notifier", "notify（実装が複数）", "メソッド参照", "23"));
    assertThat(calls.table().rows().get(0).get(2).qualifiedName()).isEqualTo(REPOSITORY);
    assertThat(calls.notes())
        .anyMatch(note -> note.contains("オーバーロードと多態を解決しない"))
        .anyMatch(note -> note.contains("コンストラクタ内の呼び出し"));
  }

  @Test
  @DisplayName("Controller template lists its endpoints and dependencies")
  void controllerTemplate() {
    ClassOverview overview = ClassOverviewGenerator.of(shop(), CONTROLLER).orElseThrow();

    assertThat(overview.template()).isEqualTo(ClassTemplate.CONTROLLER);
    assertThat(overview.summary())
        .isEqualTo(
            "OrderController は com.example.shop.web パッケージの Controller です。エンドポイント 1 件（GET 1）を提供する。依存先：OrderService。");
    assertThat(section(overview, "エンドポイント").table().texts())
        .containsExactly(List.of("get", "GET", "/api/orders/{id}", "-", "PurchaseOrder"));
  }

  @Test
  @DisplayName("Repository template shows target entity, id type, base type and declared methods")
  void repositoryTemplate() {
    ClassOverview overview = ClassOverviewGenerator.of(shop(), REPOSITORY).orElseThrow();

    assertThat(overview.template()).isEqualTo(ClassTemplate.REPOSITORY);
    assertThat(overview.summary())
        .isEqualTo(
            "PurchaseOrderRepository は com.example.shop.repository パッケージの Repository です。エンティティ PurchaseOrder（ID 型 Long）を扱う。利用元：OrderService。");
    assertThat(section(overview, "リポジトリ").keyValues())
        .extracting(kv -> kv.key() + "=" + kv.value().text())
        .containsExactly(
            "対象エンティティ=PurchaseOrder", "ID型=Long", "基底型=JpaRepository<PurchaseOrder, Long>");
    assertThat(titles(overview)).contains("宣言メソッド").doesNotContain("メソッド");
    assertThat(section(overview, "宣言メソッド").table().texts())
        .containsExactly(List.of("findByCode", "String code", "PurchaseOrder", "ソース"));
  }

  @Test
  @DisplayName("Entity template shows table, columns, relations and the matching repository")
  void entityTemplate() {
    ClassOverview overview = ClassOverviewGenerator.of(shop(), ENTITY).orElseThrow();

    assertThat(overview.template()).isEqualTo(ClassTemplate.ENTITY);
    assertThat(overview.summary())
        .isEqualTo(
            "PurchaseOrder は com.example.shop.domain パッケージのエンティティです（テーブル：purchase_orders）。カラム 1 件。関連：なし。");
    assertThat(section(overview, "対応するリポジトリ").table().texts())
        .containsExactly(List.of("PurchaseOrderRepository", "Long"));
    assertThat(section(overview, "関連").table().isEmpty()).isTrue();
    assertThat(section(overview, "関連").emptyMessage()).isEqualTo("検出されませんでした");
  }

  @Test
  @DisplayName("A class matching no template gets the common items only")
  void commonTemplate() {
    ClassOverview overview = ClassOverviewGenerator.of(shop(), UTIL).orElseThrow();

    assertThat(overview.template()).isEqualTo(ClassTemplate.COMMON);
    assertThat(overview.summary()).isEqualTo("Strings は com.example.shop.util パッケージのクラスです。");
    assertThat(titles(overview)).containsExactly("基本情報", "フィールド", "コンストラクタ", "メソッド");
    assertThat(section(overview, "基本情報").keyValues())
        .extracting(kv -> kv.key())
        .containsExactly(
            "完全修飾名", "種類", "役割", "パッケージ", "ソースファイル", "修飾子", "アノテーション", "親クラス", "インタフェース", "Javadoc",
            "コメント");
  }

  private static EndpointModel endpoint(String methodName, List<String> httpMethods) {
    return new EndpointModel(
        CONTROLLER,
        methodName,
        httpMethods,
        List.of("/api/orders"),
        true,
        null,
        VOID,
        VOID,
        List.of(),
        true,
        List.of());
  }

  private static DependencyModel dependency(
      String from, String member, TypeRef declared, String resolved, DependencyResolution how) {
    return new DependencyModel(from, member, InjectionKind.CONSTRUCTOR, declared, resolved, how);
  }

  private static RelationModel relation(String field, RelationKind kind, String target) {
    return new RelationModel(
        field, kind, type(target), false, null, null, null, List.of(), List.of(), null);
  }

  @Test
  @DisplayName("Controller summary counts endpoints per HTTP method, any-method mappings last")
  void controllerSummaryCountsHttpMethods() {
    ProjectModel model =
        project()
            .types(typeModel(CONTROLLER, TypeKind.CLASS, null, List.of(), List.of(), List.of()))
            .components(new ComponentModel(CONTROLLER, ComponentRole.CONTROLLER, List.of()))
            .endpoints(
                endpoint("remove", List.of("DELETE")),
                endpoint("list", List.of("GET")),
                endpoint("any", List.of()),
                endpoint("save", List.of("POST", "PUT")),
                endpoint("get", List.of("GET")))
            .build();

    assertThat(ClassOverviewGenerator.of(model, CONTROLLER).orElseThrow().summary())
        .isEqualTo(
            "OrderController は com.example.shop.web パッケージの Controller です。"
                + "エンドポイント 5 件（GET 2・POST 1・PUT 1・DELETE 1・全メソッド 1）を提供する。依存先：なし。");
  }

  @Test
  @DisplayName("Unresolved dependencies keep the declared type and the reason; none is なし")
  void serviceSummaryWithUnresolvedDependencies() {
    ProjectModel model =
        project()
            .types(typeModel(SERVICE, TypeKind.CLASS, null, List.of(), List.of(), List.of()))
            .components(new ComponentModel(SERVICE, ComponentRole.SERVICE, List.of()))
            .dependencies(
                dependency(
                    SERVICE,
                    "mailSender",
                    unresolvedType("JavaMailSender"),
                    null,
                    DependencyResolution.EXTERNAL),
                dependency(
                    SERVICE,
                    "notifier",
                    type("com.example.shop.service.Notifier"),
                    null,
                    DependencyResolution.AMBIGUOUS),
                dependency(
                    SERVICE,
                    "auditor",
                    type("com.example.shop.service.Auditor"),
                    "com.example.shop.service.impl.AuditorImpl",
                    DependencyResolution.SINGLE_IMPLEMENTATION))
            .build();

    assertThat(ClassOverviewGenerator.of(model, SERVICE).orElseThrow().summary())
        .isEqualTo(
            "OrderService は com.example.shop.service パッケージの Service です。公開メソッド 0 件。"
                + "利用元：なし。依存先：JavaMailSender（外部ライブラリ）、Notifier（実装が複数）、"
                + "AuditorImpl（単一実装から解決）。");
  }

  @Test
  @DisplayName("More than five names are cut to five with the remaining count; duplicates once")
  void summaryListsAtMostFiveNames() {
    String base = "com.example.shop.repository.Repo";
    List<DependencyModel> dependencies = new java.util.ArrayList<>();
    for (int i = 1; i <= 7; i++) {
      dependencies.add(
          dependency(
              SERVICE, "repo" + i, type(base + i), base + i, DependencyResolution.PROJECT_TYPE));
    }
    dependencies.add(
        dependency(CONTROLLER, "first", type(SERVICE), SERVICE, DependencyResolution.PROJECT_TYPE));
    dependencies.add(
        dependency(
            CONTROLLER, "second", type(SERVICE), SERVICE, DependencyResolution.PROJECT_TYPE));
    ProjectModel model =
        project()
            .types(typeModel(SERVICE, TypeKind.CLASS, null, List.of(), List.of(), List.of()))
            .components(new ComponentModel(SERVICE, ComponentRole.SERVICE, List.of()))
            .dependencies(dependencies.toArray(DependencyModel[]::new))
            .build();

    assertThat(ClassOverviewGenerator.of(model, SERVICE).orElseThrow().summary())
        .endsWith("利用元：OrderController。依存先：Repo1、Repo2、Repo3、Repo4、Repo5 ほか 2 件。");
  }

  @Test
  @DisplayName(
      "Classes with the same simple name in different packages are counted and named apart")
  void summaryKeepsSameSimpleNamesApart() {
    String adminController = "com.example.shop.admin.UserController";
    String apiController = "com.example.shop.api.UserController";
    String adminRepository = "com.example.shop.admin.UserRepository";
    String apiRepository = "com.example.shop.api.UserRepository";
    ProjectModel model =
        project()
            .types(typeModel(SERVICE, TypeKind.CLASS, null, List.of(), List.of(), List.of()))
            .components(new ComponentModel(SERVICE, ComponentRole.SERVICE, List.of()))
            .dependencies(
                dependency(
                    adminController,
                    "s",
                    type(SERVICE),
                    SERVICE,
                    DependencyResolution.PROJECT_TYPE),
                dependency(
                    apiController, "s", type(SERVICE), SERVICE, DependencyResolution.PROJECT_TYPE),
                dependency(
                    SERVICE,
                    "a",
                    type(adminRepository),
                    adminRepository,
                    DependencyResolution.PROJECT_TYPE),
                dependency(
                    SERVICE,
                    "b",
                    type(apiRepository),
                    apiRepository,
                    DependencyResolution.PROJECT_TYPE))
            .build();

    assertThat(ClassOverviewGenerator.of(model, SERVICE).orElseThrow().summary())
        .endsWith(
            "利用元：com.example.shop.admin.UserController、com.example.shop.api.UserController。"
                + "依存先：com.example.shop.admin.UserRepository、com.example.shop.api.UserRepository。");
  }

  @Test
  @DisplayName("Entity summary lists related entities with the multiplicity in Japanese")
  void entitySummaryListsRelations() {
    ProjectModel model =
        project()
            .types(typeModel(ENTITY, TypeKind.CLASS, null, List.of(), List.of(), List.of()))
            .entities(
                new EntityModel(
                    ENTITY,
                    EntityKind.ENTITY,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(
                        relation(
                            "customer",
                            RelationKind.MANY_TO_ONE,
                            "com.example.shop.domain.Customer"),
                        relation(
                            "products",
                            RelationKind.MANY_TO_MANY,
                            "com.example.shop.domain.Product"),
                        relation("lines", RelationKind.ONE_TO_MANY, "com.example.shop.domain.Line"),
                        relation(
                            "invoice",
                            RelationKind.ONE_TO_ONE,
                            "com.example.shop.domain.Invoice"))))
            .build();

    assertThat(ClassOverviewGenerator.of(model, ENTITY).orElseThrow().summary())
        .isEqualTo(
            "PurchaseOrder は com.example.shop.domain パッケージのエンティティです（テーブル：未指定）。カラム 0 件。"
                + "関連：Customer（多対 1）、Product（多対多）、Line（1 対多）、Invoice（1 対 1）。");
  }

  @Test
  @DisplayName("Embeddable and MappedSuperclass are named by their kind, with spaces around ASCII")
  void embeddableHeadingAndSpacing() {
    String address = "com.example.shop.domain.Address";
    ProjectModel model =
        project()
            .types(typeModel(address, TypeKind.CLASS, null, List.of(), List.of(), List.of()))
            .entities(
                new EntityModel(
                    address, EntityKind.EMBEDDABLE, null, null, null, null, List.of(), List.of()))
            .build();

    ClassOverview overview = ClassOverviewGenerator.of(model, address).orElseThrow();

    assertThat(overview.heading()).isEqualTo("Address（Embeddable）");
    assertThat(overview.summary())
        .isEqualTo("Address は com.example.shop.domain パッケージの Embeddable です。カラム 0 件。関連：なし。");
  }

  @Test
  @DisplayName("A type kind written in ASCII, such as Enum, gets spaces around it")
  void enumSpacing() {
    String status = "com.example.shop.domain.Status";
    ProjectModel model =
        project()
            .types(typeModel(status, TypeKind.ENUM, null, List.of(), List.of(), List.of()))
            .build();

    assertThat(ClassOverviewGenerator.of(model, status).orElseThrow().summary())
        .isEqualTo("Status は com.example.shop.domain パッケージの Enum です。");
  }

  @Test
  @DisplayName("An unknown class has no overview")
  void unknownClass() {
    assertThat(ClassOverviewGenerator.of(shop(), "com.example.Missing")).isEmpty();
  }
}
