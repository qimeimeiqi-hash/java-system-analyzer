package com.example.javaanalyzer.generation;

import static com.example.javaanalyzer.generation.IrFixtures.project;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.generation.diagram.ViewTransitionDiagramGenerator;
import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.EndpointRef;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.TemplateIncludeModel;
import com.example.javaanalyzer.ir.TemplateKind;
import com.example.javaanalyzer.ir.TemplateLinkModel;
import com.example.javaanalyzer.ir.ViewReturnKind;
import com.example.javaanalyzer.ir.ViewReturnModel;
import com.example.javaanalyzer.ir.ViewTemplateModel;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ViewReportGeneratorTest {

  private static final String PETS = "com.example.clinic.web.PetController";
  private static final String T = "src/main/resources/templates/";

  /** {@link #T} relative to "src/main/", the directory common to all templates of clinic(). */
  private static final String R = "resources/templates/";

  private static EndpointRef ref(String method) {
    return new EndpointRef(PETS, method);
  }

  private static EndpointModel endpoint(
      String method, String httpMethod, ViewReturnModel... returns) {
    return new EndpointModel(
        PETS,
        method,
        List.of(httpMethod),
        List.of("/pets"),
        true,
        null,
        null,
        null,
        List.of(),
        false,
        List.of(returns));
  }

  private static ViewReturnModel view(String value, String... templates) {
    return new ViewReturnModel(ViewReturnKind.VIEW, value, true, 10, List.of(templates), List.of());
  }

  /** thymeleaf-demo: list -> form -> POST -> redirect to show. */
  private static ProjectModel clinic() {
    return project()
        .views(
            new ViewTemplateModel(
                T + "pets/petList.html",
                TemplateKind.THYMELEAF,
                "pets/petList",
                List.of(
                    new TemplateLinkModel(
                        "a",
                        "th:href",
                        "@{/pets/new}",
                        "/pets/new",
                        "GET",
                        6,
                        List.of(ref("initCreationForm"))),
                    new TemplateLinkModel(
                        "a", "th:href", "@{${backUrl}}", null, "GET", 9, List.of())),
                List.of(
                    new TemplateIncludeModel(
                        "layout:decorate",
                        "~{fragments/layout}",
                        "fragments/layout",
                        List.of(T + "fragments/layout.html"),
                        2),
                    new TemplateIncludeModel("th:replace", "~{${frag}}", null, List.of(), 3))),
            new ViewTemplateModel(
                T + "pets/form.html",
                TemplateKind.THYMELEAF,
                "pets/form",
                List.of(
                    new TemplateLinkModel(
                        "form",
                        "th:action",
                        "@{/pets/new}",
                        "/pets/new",
                        "POST",
                        5,
                        List.of(ref("processCreationForm")))),
                List.of()),
            new ViewTemplateModel(
                T + "pets/show.html", TemplateKind.THYMELEAF, "pets/show", List.of(), List.of()),
            new ViewTemplateModel(
                "src/main/webapp/WEB-INF/jsp/part.jspf",
                TemplateKind.JSP,
                null,
                List.of(),
                List.of()))
        .endpoints(
            endpoint("list", "GET", view("pets/petList", T + "pets/petList.html")),
            endpoint("initCreationForm", "GET", view("pets/form", T + "pets/form.html")),
            endpoint(
                "processCreationForm",
                "POST",
                view("pets/form", T + "pets/form.html"),
                new ViewReturnModel(
                    ViewReturnKind.REDIRECT,
                    "/pets/{id}",
                    true,
                    30,
                    List.of(),
                    List.of(ref("show")))),
            endpoint("show", "GET", view("pets/show", T + "pets/show.html")),
            endpoint(
                "dynamic",
                "GET",
                new ViewReturnModel(null, "viewName()", false, 40, List.of(), List.of()),
                view("missing/page")))
        .build();
  }

  @Test
  @DisplayName(
      "Screens show relative templates, kind, view name, and one method or include per line")
  void screenTable() {
    Table table = ViewReportGenerator.screens(clinic());

    assertThat(table.headers())
        .containsExactly("テンプレート", "種類", "View 名", "表示する Controller メソッド", "部品（include）");
    assertThat(table.texts())
        .containsExactly(
            List.of(
                R + "pets/form.html",
                "THYMELEAF",
                "pets/form",
                "PetController.initCreationForm\nPetController.processCreationForm",
                "-"),
            List.of(
                R + "pets/petList.html",
                "THYMELEAF",
                "pets/petList",
                "PetController.list",
                R + "fragments/layout.html\n~{${frag}}（未解決）"),
            List.of(R + "pets/show.html", "THYMELEAF", "pets/show", "PetController.show", "-"),
            List.of("webapp/WEB-INF/jsp/part.jspf", "JSP", "未指定", "-", "-"));
  }

  @Test
  @DisplayName("Transitions follow one step and list each next screen on its own line, relative")
  void transitionTable() {
    Table table = ViewReportGenerator.transitions(clinic());

    assertThat(table.headers()).containsExactly("画面", "要素", "記述", "遷移先 Controller メソッド", "遷移後の画面");
    assertThat(table.texts())
        .containsExactly(
            List.of(
                R + "pets/form.html",
                "form（th:action）",
                "POST @{/pets/new}",
                "PetController.processCreationForm",
                R + "pets/form.html\n" + R + "pets/show.html"),
            List.of(
                R + "pets/petList.html",
                "a（th:href）",
                "GET @{/pets/new}",
                "PetController.initCreationForm",
                R + "pets/form.html"),
            List.of(R + "pets/petList.html", "a（th:href）", "GET @{${backUrl}}（未解決）", "-", "-"));
  }

  @Test
  @DisplayName("View returns that could not be matched to a template are listed with the reason")
  void unmatchedTable() {
    assertThat(ViewReportGenerator.unmatched(clinic()).texts())
        .containsExactly(
            List.of("PetController.dynamic", "不明", "viewName()", "式のため未解決"),
            List.of("PetController.dynamic", "View", "missing/page", "テンプレートが見つからない"));
  }

  @Test
  @DisplayName("The transition diagram links screens; redirects and forwards are dashed")
  void transitionDiagram() {
    String code = ViewTransitionDiagramGenerator.diagram(clinic()).orElseThrow().code();

    assertThat(code)
        .isEqualTo(
            """
            flowchart LR
              v1["pets/form"]
              v2["pets/petList"]
              v3["pets/show"]
              v4["src/main/webapp/WEB-INF/jsp/part.jspf"]
              v1 --> v1
              v1 -.-> v3
              v2 --> v1
            """);
    assertThat(ViewTransitionDiagramGenerator.diagram(project().build())).isEmpty();
  }

  private static ViewTemplateModel template(String path) {
    return new ViewTemplateModel(path, TemplateKind.JSP, null, List.of(), List.of());
  }

  @Test
  @DisplayName("The common template directory ends with a slash and is empty without a shared one")
  void templateDirectory() {
    assertThat(ViewReportGenerator.templateDirectory(clinic())).isEqualTo("src/main/");
    assertThat(
            ViewReportGenerator.templateDirectory(
                project()
                    .views(template(T + "owners/list.html"), template(T + "pets/form.html"))
                    .build()))
        .isEqualTo(T);
    assertThat(
            ViewReportGenerator.templateDirectory(
                project().views(template(T + "owners/list.html")).build()))
        .isEqualTo(T + "owners/");
    assertThat(
            ViewReportGenerator.templateDirectory(
                project().views(template("index.jsp"), template(T + "a.html")).build()))
        .isEmpty();
    assertThat(
            ViewReportGenerator.templateDirectory(
                project()
                    .views(template("src/main/webapp/a.jsp"), template("src/main/webapp2/b.jsp"))
                    .build()))
        .isEqualTo("src/main/");
    assertThat(ViewReportGenerator.templateDirectory(project().build())).isEmpty();
  }

  @Test
  @DisplayName("The location note names the common directory, and is absent without one")
  void templateDirectoryNote() {
    assertThat(ViewReportGenerator.templateDirectoryNote(clinic())).contains("テンプレートの場所：src/main/");
    assertThat(ViewReportGenerator.templateDirectoryNote(project().build())).isEmpty();
  }

  @Test
  @DisplayName("Overloaded handlers sharing a reference are narrowed by the link's HTTP method")
  void overloadedHandlersAreNarrowedByHttpMethod() {
    ProjectModel model =
        project()
            .views(
                new ViewTemplateModel(
                    T + "pets/form.html",
                    TemplateKind.THYMELEAF,
                    "pets/form",
                    List.of(
                        new TemplateLinkModel(
                            "form",
                            "th:action",
                            "@{/pets/new}",
                            "/pets/new",
                            "POST",
                            5,
                            List.of(ref("form")))),
                    List.of()),
                new ViewTemplateModel(
                    T + "pets/done.html",
                    TemplateKind.THYMELEAF,
                    "pets/done",
                    List.of(),
                    List.of()),
                new ViewTemplateModel(
                    T + "pets/show.html",
                    TemplateKind.THYMELEAF,
                    "pets/show",
                    List.of(),
                    List.of()))
            .endpoints(
                endpoint("form", "GET", view("pets/form", T + "pets/form.html")),
                endpoint(
                    "form",
                    "POST",
                    view("pets/done", T + "pets/done.html"),
                    new ViewReturnModel(
                        ViewReturnKind.REDIRECT,
                        "/pets/show",
                        true,
                        30,
                        List.of(),
                        List.of(ref("show")))),
                endpoint("show", "GET", view("pets/show", T + "pets/show.html")),
                endpoint("show", "POST", view("pets/form", T + "pets/form.html")))
            .build();

    // All templates are under pets/, which is the common directory shown relative to.
    assertThat(ViewReportGenerator.transitions(model).texts())
        .containsExactly(
            List.of(
                "form.html",
                "form（th:action）",
                "POST @{/pets/new}",
                "PetController.form",
                "done.html\nshow.html"));
  }
}
