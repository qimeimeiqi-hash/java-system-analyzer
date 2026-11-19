package com.example.javaanalyzer.analysis.plugin.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.example.javaanalyzer.analysis.ProjectAnalyzer;
import com.example.javaanalyzer.analysis.plugin.springboot.SpringBootPlugin;
import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.EndpointRef;
import com.example.javaanalyzer.ir.IssueKind;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.TemplateIncludeModel;
import com.example.javaanalyzer.ir.TemplateLinkModel;
import com.example.javaanalyzer.ir.ViewReturnKind;
import com.example.javaanalyzer.ir.ViewReturnModel;
import com.example.javaanalyzer.ir.ViewTemplateModel;
import com.example.javaanalyzer.ir.io.IrReader;
import com.example.javaanalyzer.ir.io.IrWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end check of FR-17 on the checked-in Thymeleaf and JSP sample projects. */
class ViewSampleProjectTest {

  private static final String PETS = "com.example.clinic.web.PetController";
  private static final String TEMPLATES = "src/main/resources/templates/";
  private static final String ITEMS = "com.example.shop.web.ItemController";
  private static final String JSP = "src/main/webapp/WEB-INF/jsp/";

  @TempDir Path irDir;

  private static ProjectModel analyzeSample(String name) {
    return new ProjectAnalyzer(List.of(new SpringBootPlugin(), new ViewTemplatePlugin()))
        .analyze(Path.of("src/test/resources/sample-projects", name));
  }

  private static EndpointModel endpoint(ProjectModel model, String methodName) {
    return model.endpoints().stream()
        .filter(e -> e.methodName().equals(methodName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No endpoint " + methodName));
  }

  private static ViewTemplateModel view(ProjectModel model, String path) {
    return model.views().stream()
        .filter(v -> v.path().equals(path))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No view " + path));
  }

  @Test
  @DisplayName("Thymeleaf demo: every template is listed with its view name")
  void thymeleafDemoListsScreens() {
    assertThat(analyzeSample("thymeleaf-demo").views())
        .extracting(ViewTemplateModel::path, ViewTemplateModel::viewName)
        .containsExactly(
            tuple(TEMPLATES + "error.html", "error"),
            tuple(TEMPLATES + "fragments/layout.html", "fragments/layout"),
            tuple(TEMPLATES + "pets/createOrUpdatePetForm.html", "pets/createOrUpdatePetForm"),
            tuple(TEMPLATES + "pets/petDetails.html", "pets/petDetails"),
            tuple(TEMPLATES + "pets/petList.html", "pets/petList"));
  }

  @Test
  @DisplayName("Thymeleaf demo: controller methods are linked to the screens they render")
  void thymeleafDemoLinksControllersToScreens() {
    ProjectModel model = analyzeSample("thymeleaf-demo");

    assertThat(endpoint(model, "list").viewReturns())
        .flatExtracting(ViewReturnModel::templatePaths)
        .containsExactly(TEMPLATES + "pets/petList.html");
    assertThat(endpoint(model, "initCreationForm").viewReturns())
        .flatExtracting(ViewReturnModel::templatePaths)
        .containsExactly(TEMPLATES + "pets/createOrUpdatePetForm.html");
    assertThat(endpoint(model, "showPet").viewReturns())
        .flatExtracting(ViewReturnModel::templatePaths)
        .containsExactly(TEMPLATES + "pets/petDetails.html");
    assertThat(endpoint(model, "processCreationForm").viewReturns())
        .extracting(ViewReturnModel::kind, ViewReturnModel::templatePaths, ViewReturnModel::targets)
        .containsExactly(
            tuple(
                ViewReturnKind.VIEW,
                List.of(TEMPLATES + "pets/createOrUpdatePetForm.html"),
                List.of()),
            tuple(ViewReturnKind.REDIRECT, List.of(), List.of(new EndpointRef(PETS, "showPet"))));
    assertThat(endpoint(model, "names").responseBody()).isTrue();
    assertThat(endpoint(model, "names").viewReturns()).isEmpty();
  }

  @Test
  @DisplayName("Thymeleaf demo: links and forms lead to endpoints; layouts are recorded as parts")
  void thymeleafDemoTransitions() {
    ProjectModel model = analyzeSample("thymeleaf-demo");

    assertThat(view(model, TEMPLATES + "pets/petList.html").links())
        .extracting(TemplateLinkModel::path, TemplateLinkModel::targets)
        .containsExactly(tuple("/pets/{petId}", List.of(new EndpointRef(PETS, "showPet"))));
    assertThat(view(model, TEMPLATES + "pets/createOrUpdatePetForm.html").links())
        .extracting(
            TemplateLinkModel::path, TemplateLinkModel::httpMethod, TemplateLinkModel::targets)
        .containsExactly(
            tuple("/pets/new", "POST", List.of(new EndpointRef(PETS, "processCreationForm"))),
            tuple(null, "GET", List.of()));
    assertThat(view(model, TEMPLATES + "fragments/layout.html").links())
        .extracting(TemplateLinkModel::path, TemplateLinkModel::targets)
        .containsExactly(
            tuple("/pets", List.of(new EndpointRef(PETS, "list"))),
            tuple("/pets/new", List.of(new EndpointRef(PETS, "initCreationForm"))));
    assertThat(view(model, TEMPLATES + "pets/petList.html").includes())
        .extracting(TemplateIncludeModel::attribute, TemplateIncludeModel::templatePaths)
        .containsExactly(
            tuple("layout:decorate", List.of(TEMPLATES + "fragments/layout.html")),
            tuple("th:replace", List.of(TEMPLATES + "fragments/layout.html")));
    assertThat(view(model, TEMPLATES + "error.html").links())
        .extracting(TemplateLinkModel::path, TemplateLinkModel::targets)
        .containsExactly(tuple("/", List.of()));
  }

  @Test
  @DisplayName("JSP demo: view names come from spring.mvc.view.prefix/suffix and are linked")
  void jspDemoLinksControllersToScreens() {
    ProjectModel model = analyzeSample("jsp-demo");

    assertThat(model.views())
        .extracting(ViewTemplateModel::path, ViewTemplateModel::viewName)
        .containsExactly(
            tuple(JSP + "common/footer.jsp", "common/footer"),
            tuple(JSP + "common/header.jspf", null),
            tuple(JSP + "items/detail.jsp", "items/detail"),
            tuple(JSP + "items/list.jsp", "items/list"));
    assertThat(endpoint(model, "detail").viewReturns())
        .flatExtracting(ViewReturnModel::templatePaths)
        .containsExactly(JSP + "items/detail.jsp");
    assertThat(endpoint(model, "home").viewReturns().get(0).targets())
        .containsExactlyInAnyOrder(
            new EndpointRef(ITEMS, "list"), new EndpointRef(ITEMS, "create"));
    assertThat(endpoint(model, "count").responseBody()).isTrue();
    assertThat(model.issues()).noneMatch(i -> i.kind() == IssueKind.VIEW_SETTING_UNRESOLVED);
  }

  @Test
  @DisplayName("JSP demo: c:url, the context path, form:form and includes are read")
  void jspDemoTransitions() {
    ProjectModel model = analyzeSample("jsp-demo");
    ViewTemplateModel list = view(model, JSP + "items/list.jsp");

    assertThat(list.links())
        .extracting(
            TemplateLinkModel::element,
            TemplateLinkModel::path,
            TemplateLinkModel::httpMethod,
            TemplateLinkModel::targets)
        .containsExactly(
            tuple("a", null, "GET", List.of()),
            tuple("form:form", "/items", "POST", List.of(new EndpointRef(ITEMS, "create"))));
    assertThat(list.includes())
        .extracting(TemplateIncludeModel::attribute, TemplateIncludeModel::templatePaths)
        .containsExactly(
            tuple("include", List.of(JSP + "common/header.jspf")),
            tuple("jsp:include", List.of(JSP + "common/footer.jsp")));
    assertThat(view(model, JSP + "common/header.jspf").links())
        .extracting(TemplateLinkModel::path, TemplateLinkModel::targets)
        .containsExactly(tuple("/items", List.of(new EndpointRef(ITEMS, "list"))));
    assertThat(view(model, JSP + "items/detail.jsp").includes())
        .flatExtracting(TemplateIncludeModel::templatePaths)
        .containsExactly(JSP + "common/footer.jsp");
  }

  @Test
  @DisplayName("Both demos: the views survive a JSON round trip and are saved in project.json")
  void viewsSurviveRoundTrip() throws Exception {
    for (String sample : List.of("thymeleaf-demo", "jsp-demo")) {
      ProjectModel model = analyzeSample(sample);
      Path dir = Files.createDirectories(irDir.resolve(sample));

      IrWriter.write(model, dir);

      assertThat(IrReader.read(dir)).isEqualTo(model);
      assertThat(Files.readString(dir.resolve("project.json"))).contains("\"views\" : [ {");
    }
  }
}
