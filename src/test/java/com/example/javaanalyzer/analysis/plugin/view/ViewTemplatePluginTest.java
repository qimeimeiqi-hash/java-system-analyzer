package com.example.javaanalyzer.analysis.plugin.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.example.javaanalyzer.analysis.AnalysisTestSupport;
import com.example.javaanalyzer.analysis.ProjectAnalyzer;
import com.example.javaanalyzer.analysis.plugin.springboot.SpringBootPlugin;
import com.example.javaanalyzer.ir.AnalysisIssue;
import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.EndpointRef;
import com.example.javaanalyzer.ir.IssueKind;
import com.example.javaanalyzer.ir.IssueSeverity;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.TemplateIncludeModel;
import com.example.javaanalyzer.ir.TemplateKind;
import com.example.javaanalyzer.ir.TemplateLinkModel;
import com.example.javaanalyzer.ir.ViewReturnModel;
import com.example.javaanalyzer.ir.ViewTemplateModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ViewTemplatePluginTest {

  private static final String THYMELEAF_POM =
      """
      <project><dependencies><dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-thymeleaf</artifactId>
      </dependency></dependencies></project>
      """;
  private static final String PLAIN_POM = "<project><dependencies/></project>";
  private static final String OWNER_CONTROLLER =
      """
      package com.example.web;
      import org.springframework.stereotype.Controller;
      import org.springframework.web.bind.annotation.*;
      @Controller public class OwnerController {
        @GetMapping("/owners") public String list() { return "owners/list"; }
        @GetMapping("/owners/{ownerId}") public String show() { return "owners/details :: main"; }
        @GetMapping("/owners/new") public String form() { return "owners/missing"; }
        @PostMapping("/owners") public String create() { return "redirect:/owners/{ownerId}"; }
        @PostMapping("/owners/{ownerId}/delete") public String delete() { return "forward:/owners"; }
      }
      """;

  @TempDir Path projectRoot;

  private void writeFile(String relativePath, String content) throws IOException {
    Path file = projectRoot.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  private ProjectModel analyze(String... sources) {
    for (String source : sources) {
      AnalysisTestSupport.writeSource(projectRoot, source);
    }
    return new ProjectAnalyzer(List.of(new SpringBootPlugin(), new ViewTemplatePlugin()))
        .analyze(projectRoot);
  }

  private static ViewTemplateModel view(ProjectModel model, String path) {
    return model.views().stream()
        .filter(v -> v.path().equals(path))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No view " + path + " in " + model.views()));
  }

  private static EndpointModel endpoint(ProjectModel model, String methodName) {
    return model.endpoints().stream()
        .filter(e -> e.methodName().equals(methodName))
        .findFirst()
        .orElseThrow();
  }

  private static EndpointRef ref(String methodName) {
    return new EndpointRef("com.example.web.OwnerController", methodName);
  }

  @Test
  @DisplayName("Thymeleaf templates get view names from their path under templates/")
  void listsThymeleafTemplatesWithViewNames() throws IOException {
    writeFile("pom.xml", THYMELEAF_POM);
    writeFile("src/main/resources/templates/owners/list.html", "<html></html>");
    writeFile("src/main/resources/templates/index.html", "<html></html>");
    writeFile("src/main/resources/templates/notes.txt", "not a template");
    writeFile("src/main/resources/static/page.html", "<html></html>");

    ProjectModel model = analyze(OWNER_CONTROLLER);

    assertThat(model.views())
        .extracting(ViewTemplateModel::path, ViewTemplateModel::kind, ViewTemplateModel::viewName)
        .containsExactly(
            tuple("src/main/resources/templates/index.html", TemplateKind.THYMELEAF, "index"),
            tuple(
                "src/main/resources/templates/owners/list.html",
                TemplateKind.THYMELEAF,
                "owners/list"));
  }

  @Test
  @DisplayName("Without Thymeleaf in pom.xml, html files under templates/ are not Thymeleaf views")
  void ignoresTemplatesDirectoryWithoutThymeleaf() throws IOException {
    writeFile("pom.xml", PLAIN_POM);
    writeFile("src/main/resources/templates/index.html", "<html></html>");

    assertThat(analyze(OWNER_CONTROLLER).views()).isEmpty();
  }

  @Test
  @DisplayName("Returned view names are linked to templates; a fragment selector is ignored")
  void linksViewNamesToTemplates() throws IOException {
    writeFile("pom.xml", THYMELEAF_POM);
    writeFile("src/main/resources/templates/owners/list.html", "<html></html>");
    writeFile("src/main/resources/templates/owners/details.html", "<html></html>");

    ProjectModel model = analyze(OWNER_CONTROLLER);

    assertThat(endpoint(model, "list").viewReturns())
        .flatExtracting(ViewReturnModel::templatePaths)
        .containsExactly("src/main/resources/templates/owners/list.html");
    assertThat(endpoint(model, "show").viewReturns())
        .flatExtracting(ViewReturnModel::templatePaths)
        .containsExactly("src/main/resources/templates/owners/details.html");
    assertThat(endpoint(model, "form").viewReturns().get(0).templatePaths()).isEmpty();
  }

  @Test
  @DisplayName("redirect: matches GET endpoints by path; forward: matches any method")
  void linksRedirectsAndForwardsToEndpoints() throws IOException {
    writeFile("pom.xml", THYMELEAF_POM);

    ProjectModel model = analyze(OWNER_CONTROLLER);

    assertThat(endpoint(model, "create").viewReturns().get(0).targets())
        .containsExactly(ref("show"));
    assertThat(endpoint(model, "delete").viewReturns().get(0).targets())
        .containsExactlyInAnyOrder(ref("list"), ref("create"));
  }

  @Test
  @DisplayName("Thymeleaf links and forms are read and linked to endpoints by path and method")
  void readsThymeleafLinks() throws IOException {
    writeFile("pom.xml", THYMELEAF_POM);
    writeFile(
        "src/main/resources/templates/owners/list.html",
        """
        <html xmlns:th="http://www.thymeleaf.org">
        <a href="/fallback" th:href="@{/owners/{id}(id=${owner.id})}">Show</a>
        <a th:href="@{/owners/new}">New</a>
        <form th:action="@{/owners}" method="post"></form>
        <a th:href="@{${dynamicUrl}}">Dynamic</a>
        <a href="edit">Relative</a>
        <a href="https://example.com/">External</a>
        <a href="#top">Anchor</a>
        <!-- <a th:href="@{/commented}">Commented</a> -->
        <script>var s = '<a href="/in-script">x</a>';</script>
        </html>
        """);

    ProjectModel model = analyze(OWNER_CONTROLLER);
    List<TemplateLinkModel> links =
        view(model, "src/main/resources/templates/owners/list.html").links();

    assertThat(links)
        .extracting(
            TemplateLinkModel::element,
            TemplateLinkModel::attribute,
            TemplateLinkModel::path,
            TemplateLinkModel::httpMethod,
            TemplateLinkModel::line)
        .containsExactly(
            tuple("a", "th:href", "/owners/{id}", "GET", 2),
            tuple("a", "th:href", "/owners/new", "GET", 3),
            tuple("form", "th:action", "/owners", "POST", 4),
            tuple("a", "th:href", null, "GET", 5),
            tuple("a", "href", null, "GET", 6));
    assertThat(links)
        .extracting(TemplateLinkModel::targets)
        .containsExactly(
            List.of(ref("show")),
            List.of(ref("form")),
            List.of(ref("create")),
            List.of(),
            List.of());
    assertThat(links.get(3).value()).isEqualTo("@{${dynamicUrl}}");
  }

  @Test
  @DisplayName("Thymeleaf fragments and layouts are recorded with the template they include")
  void readsThymeleafIncludes() throws IOException {
    writeFile("pom.xml", THYMELEAF_POM);
    writeFile("src/main/resources/templates/fragments/layout.html", "<html></html>");
    writeFile(
        "src/main/resources/templates/owners/list.html",
        """
        <html layout:decorate="~{fragments/layout}">
        <head th:replace="~{fragments/layout :: head(title='Owners')}"></head>
        <div th:insert="fragments/missing :: menu"></div>
        <div th:replace="~{${chosen} :: part}"></div>
        <div th:replace="~{:: localPart}"></div>
        </html>
        """);

    ProjectModel model = analyze(OWNER_CONTROLLER);

    assertThat(view(model, "src/main/resources/templates/owners/list.html").includes())
        .extracting(
            TemplateIncludeModel::attribute,
            TemplateIncludeModel::templateName,
            TemplateIncludeModel::templatePaths,
            TemplateIncludeModel::line)
        .containsExactly(
            tuple(
                "layout:decorate",
                "fragments/layout",
                List.of("src/main/resources/templates/fragments/layout.html"),
                1),
            tuple(
                "th:replace",
                "fragments/layout",
                List.of("src/main/resources/templates/fragments/layout.html"),
                2),
            tuple("th:insert", "fragments/missing", List.of(), 3),
            tuple("th:replace", null, List.of(), 4));
  }

  @Test
  @DisplayName("JSP view names use spring.mvc.view.prefix/suffix; links and includes are read")
  void readsJspWithViewSettings() throws IOException {
    writeFile("pom.xml", PLAIN_POM);
    writeFile(
        "src/main/resources/application.properties",
        "spring.mvc.view.prefix=/WEB-INF/jsp/\nspring.mvc.view.suffix=.jsp\n");
    writeFile("src/main/webapp/WEB-INF/jsp/common/header.jspf", "<div>header</div>");
    writeFile("src/main/webapp/WEB-INF/jsp/common/footer.jsp", "<div>footer</div>");
    writeFile(
        "src/main/webapp/WEB-INF/jsp/owners/list.jsp",
        """
        <%@ include file="../common/header.jspf" %>
        <%-- <a href="/commented">x</a> --%>
        <a href="<c:url value='/owners/new'/>">New</a>
        <a href="${pageContext.request.contextPath}/owners">List</a>
        <a href="<c:url value='/owners/${owner.id}'/>">Show</a>
        <form:form modelAttribute="owner" action="/owners"></form:form>
        <form action="/owners" method="get"></form>
        <jsp:include page="/WEB-INF/jsp/common/footer.jsp"/>
        """);

    ProjectModel model = analyze(OWNER_CONTROLLER);
    ViewTemplateModel list = view(model, "src/main/webapp/WEB-INF/jsp/owners/list.jsp");

    assertThat(list.kind()).isEqualTo(TemplateKind.JSP);
    assertThat(list.viewName()).isEqualTo("owners/list");
    assertThat(view(model, "src/main/webapp/WEB-INF/jsp/common/header.jspf").viewName()).isNull();
    assertThat(list.links())
        .extracting(
            TemplateLinkModel::element,
            TemplateLinkModel::path,
            TemplateLinkModel::httpMethod,
            TemplateLinkModel::targets)
        .containsExactly(
            tuple("a", "/owners/new", "GET", List.of(ref("form"))),
            tuple("a", "/owners", "GET", List.of(ref("list"))),
            tuple("a", null, "GET", List.of()),
            tuple("form:form", "/owners", "POST", List.of(ref("create"))),
            tuple("form", "/owners", "GET", List.of(ref("list"))));
    assertThat(list.includes())
        .extracting(TemplateIncludeModel::attribute, TemplateIncludeModel::templatePaths)
        .containsExactly(
            tuple("include", List.of("src/main/webapp/WEB-INF/jsp/common/header.jspf")),
            tuple("jsp:include", List.of("src/main/webapp/WEB-INF/jsp/common/footer.jsp")));
    assertThat(endpoint(model, "list").viewReturns().get(0).templatePaths())
        .containsExactly("src/main/webapp/WEB-INF/jsp/owners/list.jsp");
    assertThat(model.issues()).noneMatch(i -> i.kind() == IssueKind.VIEW_SETTING_UNRESOLVED);
  }

  @Test
  @DisplayName("A JSP without spring.mvc.view.prefix has no view name and a warning is reported")
  void warnsWhenJspViewPrefixIsMissing() throws IOException {
    writeFile("pom.xml", PLAIN_POM);
    writeFile("src/main/webapp/WEB-INF/jsp/owners/list.jsp", "<html></html>");

    ProjectModel model = analyze(OWNER_CONTROLLER);

    assertThat(view(model, "src/main/webapp/WEB-INF/jsp/owners/list.jsp").viewName()).isNull();
    assertThat(endpoint(model, "list").viewReturns().get(0).templatePaths()).isEmpty();
    assertThat(model.issues())
        .filteredOn(i -> i.kind() == IssueKind.VIEW_SETTING_UNRESOLVED)
        .extracting(AnalysisIssue::severity, AnalysisIssue::file)
        .containsExactly(tuple(IssueSeverity.WARNING, null));
  }

  @Test
  @DisplayName("YAML settings are read; a profile-specific document does not override them")
  void readsYamlSettingsIgnoringProfileDocuments() throws IOException {
    writeFile("pom.xml", PLAIN_POM);
    writeFile(
        "src/main/resources/application.yml",
        """
        spring:
          mvc:
            view:
              prefix: /WEB-INF/views/
              suffix: .jsp
        ---
        spring:
          config:
            activate:
              on-profile: other
          mvc:
            view:
              prefix: /WEB-INF/other/
        """);
    writeFile("src/main/webapp/WEB-INF/views/owners/list.jsp", "<html></html>");

    ProjectModel model = analyze(OWNER_CONTROLLER);

    assertThat(view(model, "src/main/webapp/WEB-INF/views/owners/list.jsp").viewName())
        .isEqualTo("owners/list");
  }

  @Test
  @DisplayName("A placeholder in the view settings is not resolved and is reported")
  void reportsPlaceholderSettings() throws IOException {
    writeFile("pom.xml", PLAIN_POM);
    writeFile(
        "src/main/resources/application.properties", "spring.mvc.view.prefix=${VIEW_PREFIX}\n");
    writeFile("src/main/webapp/WEB-INF/jsp/owners/list.jsp", "<html></html>");

    ProjectModel model = analyze(OWNER_CONTROLLER);

    assertThat(view(model, "src/main/webapp/WEB-INF/jsp/owners/list.jsp").viewName()).isNull();
    assertThat(model.issues())
        .filteredOn(i -> i.kind() == IssueKind.VIEW_SETTING_UNRESOLVED)
        .extracting(AnalysisIssue::file)
        .containsExactly("src/main/resources/application.properties");
  }

  @Test
  @DisplayName("A custom spring.thymeleaf.prefix and suffix decide where templates are found")
  void usesCustomThymeleafLocation() throws IOException {
    writeFile("pom.xml", THYMELEAF_POM);
    writeFile(
        "src/main/resources/application.properties",
        "spring.thymeleaf.prefix=classpath:/views/\nspring.thymeleaf.suffix=.xhtml\n");
    writeFile("src/main/resources/views/owners/list.xhtml", "<html></html>");
    writeFile("src/main/resources/templates/ignored.html", "<html></html>");

    ProjectModel model = analyze(OWNER_CONTROLLER);

    assertThat(model.views())
        .extracting(ViewTemplateModel::path, ViewTemplateModel::viewName)
        .containsExactly(tuple("src/main/resources/views/owners/list.xhtml", "owners/list"));
  }

  @Test
  @DisplayName("A JSP tag with double quotes inside a double-quoted attribute is kept whole")
  void readsJspTagNestedInSameQuotes() throws IOException {
    writeFile("pom.xml", PLAIN_POM);
    writeFile(
        "src/main/webapp/WEB-INF/jsp/owners/list.jsp",
        "<a href=\"<spring:url value=\"/owners/new\" htmlEscape=\"true\" />\" class=\"x\">New</a>\n");

    ProjectModel model = analyze(OWNER_CONTROLLER);
    List<TemplateLinkModel> links =
        view(model, "src/main/webapp/WEB-INF/jsp/owners/list.jsp").links();

    assertThat(links)
        .extracting(TemplateLinkModel::value, TemplateLinkModel::path, TemplateLinkModel::targets)
        .containsExactly(
            tuple(
                "<spring:url value=\"/owners/new\" htmlEscape=\"true\" />",
                "/owners/new",
                List.of(ref("form"))));
  }

  @Test
  @DisplayName("A Thymeleaf link written as one quoted literal is a literal path")
  void readsQuotedLiteralThymeleafLink() throws IOException {
    writeFile("pom.xml", THYMELEAF_POM);
    writeFile(
        "src/main/resources/templates/owners/list.html",
        """
        <a th:href="@{'/owners?page=1'}">First</a>
        <a th:href="@{'/owners?page=' + ${i}}">Page</a>
        <a th:href="@{'/owners?page=__${last}__'}">Last</a>
        """);

    ProjectModel model = analyze(OWNER_CONTROLLER);

    assertThat(view(model, "src/main/resources/templates/owners/list.html").links())
        .extracting(TemplateLinkModel::path, TemplateLinkModel::targets)
        .containsExactly(
            tuple("/owners?page=1", List.of(ref("list"))),
            tuple(null, List.of()),
            tuple(null, List.of()));
  }

  @Test
  @DisplayName("Broken markup and invalid UTF-8 do not stop the analysis")
  void toleratesBrokenTemplates() throws IOException {
    writeFile("pom.xml", THYMELEAF_POM);
    writeFile(
        "src/main/resources/templates/broken.html",
        "<a th:href=\"@{/owners}\">ok</a>\n<a href=\"/never-closed>\n<div");
    Path invalid = projectRoot.resolve("src/main/resources/templates/binary.html");
    Files.write(invalid, new byte[] {'<', 'a', ' ', (byte) 0xff, (byte) 0xfe, '>'});

    ProjectModel model = analyze(OWNER_CONTROLLER);

    assertThat(view(model, "src/main/resources/templates/broken.html").links())
        .extracting(TemplateLinkModel::path)
        .containsExactly("/owners");
    assertThat(view(model, "src/main/resources/templates/binary.html").links()).isEmpty();
  }
}
