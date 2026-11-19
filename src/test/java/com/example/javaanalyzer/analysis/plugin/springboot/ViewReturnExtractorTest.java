package com.example.javaanalyzer.analysis.plugin.springboot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.example.javaanalyzer.analysis.AnalysisContext;
import com.example.javaanalyzer.analysis.AnalysisTestSupport;
import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.ViewReturnKind;
import com.example.javaanalyzer.ir.ViewReturnModel;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ViewReturnExtractorTest {

  private static final String HEADER =
      """
      package com.example.web;
      import org.springframework.stereotype.Controller;
      import org.springframework.web.bind.annotation.*;
      import org.springframework.web.servlet.ModelAndView;
      """;

  @TempDir Path projectRoot;

  private EndpointModel endpoint(String controllerBody, String methodName) {
    AnalysisContext context =
        AnalysisTestSupport.contextOf(
            projectRoot,
            HEADER + "@Controller public class PageController {\n" + controllerBody + "}\n");
    new RequestMappingAnalyzer().analyze(context);
    return context.toProjectModel().endpoints().stream()
        .filter(e -> e.methodName().equals(methodName))
        .findFirst()
        .orElseThrow();
  }

  private List<ViewReturnModel> viewReturns(String controllerBody) {
    return endpoint(controllerBody, "handle").viewReturns();
  }

  @Test
  @DisplayName("A returned string literal is a view name; redirect: and forward: are URLs")
  void classifiesReturnedLiterals() {
    List<ViewReturnModel> returns =
        viewReturns(
            """
              @GetMapping("/p") public String handle(int mode) {
                if (mode == 1) { return "redirect:/owners"; }
                if (mode == 2) { return "forward:/home"; }
                return "owners/list";
              }
            """);

    assertThat(returns)
        .extracting(ViewReturnModel::kind, ViewReturnModel::value, ViewReturnModel::resolved)
        .containsExactly(
            tuple(ViewReturnKind.REDIRECT, "/owners", true),
            tuple(ViewReturnKind.FORWARD, "/home", true),
            tuple(ViewReturnKind.VIEW, "owners/list", true));
    assertThat(returns.get(2).line()).isEqualTo(9);
  }

  @Test
  @DisplayName("Both branches of a conditional return are extracted")
  void extractsBothBranchesOfConditional() {
    List<ViewReturnModel> returns =
        viewReturns(
            """
              @PostMapping("/p") public String handle(boolean error) {
                return error ? "owners/form" : "redirect:/owners/{ownerId}";
              }
            """);

    assertThat(returns)
        .extracting(ViewReturnModel::kind, ViewReturnModel::value)
        .containsExactly(
            tuple(ViewReturnKind.VIEW, "owners/form"),
            tuple(ViewReturnKind.REDIRECT, "/owners/{ownerId}"));
  }

  @Test
  @DisplayName("A static final String constant of the same class is resolved to its value")
  void resolvesConstantOfSameClass() {
    List<ViewReturnModel> returns =
        viewReturns(
            """
              private static final String FORM = "owners/" + "form";
              @GetMapping("/p") public String handle() { return FORM; }
            """);

    assertThat(returns)
        .extracting(ViewReturnModel::kind, ViewReturnModel::value, ViewReturnModel::resolved)
        .containsExactly(tuple(ViewReturnKind.VIEW, "owners/form", true));
  }

  @Test
  @DisplayName("A constant shadowed by a local variable is not resolved")
  void doesNotResolveShadowedConstant() {
    List<ViewReturnModel> returns =
        viewReturns(
            """
              private static final String FORM = "owners/form";
              @GetMapping("/p") public String handle(String FORM) { return FORM; }
            """);

    assertThat(returns)
        .extracting(ViewReturnModel::kind, ViewReturnModel::value, ViewReturnModel::resolved)
        .containsExactly(tuple(null, "FORM", false));
  }

  @Test
  @DisplayName("A non-final field, a method call and a concatenation are kept as written")
  void keepsExpressionsAsWritten() {
    List<ViewReturnModel> returns =
        viewReturns(
            """
              private String view = "x";
              @GetMapping("/p") public String handle(int id, int mode) {
                if (mode == 1) { return view; }
                if (mode == 2) { return pick(); }
                return "redirect:/owners/" + id;
              }
              private String pick() { return "never/extracted"; }
            """);

    assertThat(returns)
        .extracting(ViewReturnModel::kind, ViewReturnModel::value, ViewReturnModel::resolved)
        .containsExactly(
            tuple(null, "view", false),
            tuple(null, "pick()", false),
            tuple(ViewReturnKind.REDIRECT, "\"redirect:/owners/\" + id", false));
  }

  @Test
  @DisplayName("Returns inside a lambda or an anonymous class do not belong to the method")
  void ignoresReturnsOfNestedCode() {
    List<ViewReturnModel> returns =
        viewReturns(
            """
              @GetMapping("/p") public String handle() {
                java.util.function.Supplier<String> s = () -> { return "lambda/view"; };
                Object o = new Object() { public String toString() { return "anon/view"; } };
                return "real/view";
              }
            """);

    assertThat(returns).extracting(ViewReturnModel::value).containsExactly("real/view");
  }

  @Test
  @DisplayName("A ModelAndView's view name comes from its constructor and setViewName")
  void extractsModelAndViewNames() {
    List<ViewReturnModel> returns =
        viewReturns(
            """
              @GetMapping("/p") public ModelAndView handle(boolean empty) {
                ModelAndView mav = new ModelAndView("owners/details");
                if (empty) { mav.setViewName("redirect:/owners"); }
                ModelAndView blank = new ModelAndView();
                return mav;
              }
            """);

    assertThat(returns)
        .extracting(ViewReturnModel::kind, ViewReturnModel::value)
        .containsExactly(
            tuple(ViewReturnKind.VIEW, "owners/details"),
            tuple(ViewReturnKind.REDIRECT, "/owners"));
  }

  @Test
  @DisplayName("Returning null records nothing; the default view name is not inferred")
  void recordsNothingForNullOrVoid() {
    assertThat(viewReturns("@GetMapping(\"/p\") public String handle() { return null; }"))
        .isEmpty();
    assertThat(viewReturns("@GetMapping(\"/p\") public void handle() { }")).isEmpty();
  }

  @Test
  @DisplayName("@ResponseBody and ResponseEntity make the return value a body, not a view")
  void responseBodyHasNoViewReturns() {
    String body =
        """
          @ResponseBody @GetMapping("/a") public String handle() { return "not/a/view"; }
          @GetMapping("/b") public org.springframework.http.ResponseEntity<String> entity() {
            return null;
          }
          @GetMapping("/c") public String page() { return "a/view"; }
        """;

    EndpointModel annotated = endpoint(body, "handle");
    EndpointModel entity = endpoint(body, "entity");
    EndpointModel page = endpoint(body, "page");

    assertThat(annotated.responseBody()).isTrue();
    assertThat(annotated.viewReturns()).isEmpty();
    assertThat(entity.responseBody()).isTrue();
    assertThat(page.responseBody()).isFalse();
    assertThat(page.viewReturns()).extracting(ViewReturnModel::value).containsExactly("a/view");
  }

  @Test
  @DisplayName("Every endpoint of a @RestController is a response body")
  void restControllerEndpointsAreBodies() {
    AnalysisContext context =
        AnalysisTestSupport.contextOf(
            projectRoot,
            HEADER
                + """
                @RestController public class Api {
                  @GetMapping("/x") public String get() { return "plain text"; }
                }
                """);
    new RequestMappingAnalyzer().analyze(context);

    EndpointModel endpoint = context.toProjectModel().endpoints().get(0);

    assertThat(endpoint.responseBody()).isTrue();
    assertThat(endpoint.viewReturns()).isEmpty();
  }
}
