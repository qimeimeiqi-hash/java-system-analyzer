package com.example.javaanalyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.EndpointRef;
import com.example.javaanalyzer.ir.TemplateKind;
import com.example.javaanalyzer.ir.TemplateLinkModel;
import com.example.javaanalyzer.ir.TypeRef;
import com.example.javaanalyzer.ir.ViewReturnKind;
import com.example.javaanalyzer.ir.ViewReturnModel;
import com.example.javaanalyzer.ir.ViewTemplateModel;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ViewLinkerTest {

  private static final String CONTROLLER = "com.example.web.PageController";
  private static final TypeRef STRING = new TypeRef("String", "java.lang.String", List.of());

  private static EndpointModel endpoint(
      String methodName, List<String> httpMethods, List<String> paths, boolean pathsResolved) {
    return new EndpointModel(
        CONTROLLER,
        methodName,
        httpMethods,
        paths,
        pathsResolved,
        null,
        STRING,
        STRING,
        List.of(),
        false,
        List.of());
  }

  private static EndpointModel returning(ViewReturnModel viewReturn) {
    return endpoint("source", List.of("GET"), List.of("/source"), true)
        .withViewReturns(List.of(viewReturn));
  }

  private static ViewReturnModel viewReturn(ViewReturnKind kind, String value, boolean resolved) {
    return new ViewReturnModel(kind, value, resolved, 1, List.of(), List.of());
  }

  private static ViewTemplateModel template(String path, String viewName) {
    return new ViewTemplateModel(path, TemplateKind.THYMELEAF, viewName, List.of(), List.of());
  }

  private static List<EndpointRef> linkTargets(
      String path, String httpMethod, List<EndpointModel> endpoints) {
    TemplateLinkModel link =
        new TemplateLinkModel("a", "href", path, path, httpMethod, 1, List.of());
    ViewTemplateModel view =
        new ViewTemplateModel("t.html", TemplateKind.THYMELEAF, "t", List.of(link), List.of());
    return new ViewLinker(endpoints, List.of(view)).linkedViews().get(0).links().get(0).targets();
  }

  private static EndpointRef ref(String methodName) {
    return new EndpointRef(CONTROLLER, methodName);
  }

  @Test
  @DisplayName("Paths match ignoring variable names, query, fragment and a trailing slash")
  void normalizesPaths() {
    assertThat(ViewLinker.normalize("/owners/{id}/edit?x=1#top"))
        .isEqualTo(ViewLinker.normalize("/owners/{ownerId:\\d+}/edit/"));
    assertThat(ViewLinker.normalize("/")).isEqualTo("/");
    assertThat(ViewLinker.normalize("//a//b/")).isEqualTo("/a/b");
  }

  @Test
  @DisplayName("A literal segment does not match a path variable")
  void literalSegmentDoesNotMatchVariable() {
    List<EndpointModel> endpoints =
        List.of(
            endpoint("show", List.of("GET"), List.of("/owners/{id}"), true),
            endpoint("create", List.of("GET"), List.of("/owners/new"), true));

    assertThat(linkTargets("/owners/new", "GET", endpoints)).containsExactly(ref("create"));
    assertThat(linkTargets("/owners/{x}", "GET", endpoints)).containsExactly(ref("show"));
  }

  @Test
  @DisplayName("The HTTP method must match unless the mapping accepts any method")
  void matchesHttpMethod() {
    List<EndpointModel> endpoints =
        List.of(
            endpoint("post", List.of("POST"), List.of("/a"), true),
            endpoint("any", List.of(), List.of("/a"), true),
            endpoint("get", List.of("GET"), List.of("/a"), true));

    assertThat(linkTargets("/a", "POST", endpoints)).containsExactly(ref("post"), ref("any"));
  }

  @Test
  @DisplayName("Endpoints with unresolved paths and relative URLs are never matched")
  void skipsUnresolvedPathsAndRelativeUrls() {
    List<EndpointModel> endpoints = List.of(endpoint("c", List.of("GET"), List.of("PATH"), false));

    assertThat(linkTargets("PATH", "GET", endpoints)).isEmpty();
    assertThat(linkTargets(null, "GET", endpoints)).isEmpty();
  }

  @Test
  @DisplayName("Only resolved view names are linked to templates")
  void linksOnlyResolvedViewNames() {
    List<ViewTemplateModel> views = List.of(template("a.html", "a"));

    ViewLinker linker =
        new ViewLinker(
            List.of(
                returning(viewReturn(ViewReturnKind.VIEW, "a", true)),
                returning(viewReturn(null, "a", false))),
            views);

    assertThat(linker.linkedEndpoints())
        .extracting(e -> e.viewReturns().get(0).templatePaths())
        .containsExactly(List.of("a.html"), List.of());
  }

  @Test
  @DisplayName("A view name defined by two templates is linked to both")
  void linksAllTemplatesOfViewName() {
    ViewLinker linker =
        new ViewLinker(
            List.of(returning(viewReturn(ViewReturnKind.VIEW, "home", true))),
            List.of(template("b/home.jsp", "home"), template("a/home.html", "home")));

    assertThat(linker.linkedEndpoints().get(0).viewReturns().get(0).templatePaths())
        .containsExactly("a/home.html", "b/home.jsp");
    assertThat(linker.linkedViews())
        .extracting(ViewTemplateModel::path)
        .containsExactly("a/home.html", "b/home.jsp");
  }

  @Test
  @DisplayName("An absolute redirect URL to another site matches no endpoint")
  void externalRedirectHasNoTarget() {
    EndpointModel target = endpoint("t", List.of("GET"), List.of("/x"), true);
    ViewLinker linker =
        new ViewLinker(
            List.of(
                returning(viewReturn(ViewReturnKind.REDIRECT, "https://example.com/x", true)),
                target),
            List.of());

    assertThat(linker.linkedEndpoints().get(0).viewReturns().get(0).targets()).isEmpty();
  }
}
