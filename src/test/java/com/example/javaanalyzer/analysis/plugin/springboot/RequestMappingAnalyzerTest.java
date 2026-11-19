package com.example.javaanalyzer.analysis.plugin.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.analysis.AnalysisContext;
import com.example.javaanalyzer.analysis.AnalysisTestSupport;
import com.example.javaanalyzer.ir.EndpointModel;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RequestMappingAnalyzerTest {

  @TempDir Path projectRoot;

  private List<EndpointModel> analyze(String... sources) {
    AnalysisContext context = AnalysisTestSupport.contextOf(projectRoot, sources);
    new RequestMappingAnalyzer().analyze(context);
    return context.toProjectModel().endpoints();
  }

  private static EndpointModel endpoint(List<EndpointModel> endpoints, String methodName) {
    return endpoints.stream()
        .filter(e -> e.methodName().equals(methodName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No endpoint " + methodName + " in " + endpoints));
  }

  @Test
  @DisplayName("A return type that cannot be determined keeps the endpoint with its written text")
  void keepsEndpointWithUndeterminedReturnType() {
    List<EndpointModel> endpoints =
        analyze(
            """
            package com.example;
            import com.other.*;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController public class Api {
              @GetMapping("/x") public SomeResult get() { return null; }
            }
            """);

    EndpointModel endpoint = endpoint(endpoints, "get");

    assertThat(endpoint.responseType().text()).isEqualTo("SomeResult");
    assertThat(endpoint.responseType().qualifiedName()).isNull();
  }

  @Test
  @DisplayName("A List returned through import java.util.* keeps the endpoint")
  void keepsEndpointReturningOnDemandImportedList() {
    List<EndpointModel> endpoints =
        analyze(
            """
            package com.example;
            import java.util.*;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController public class Api {
              @GetMapping("/x") public List<String> get() { return null; }
            }
            """);

    assertThat(endpoint(endpoints, "get").responseType().text()).isEqualTo("List<String>");
  }

  @Test
  @DisplayName("ResponseEntity imported on demand is removed from the response type")
  void unwrapsOnDemandImportedResponseEntity() {
    List<EndpointModel> endpoints =
        analyze(
            """
            package com.example;
            import org.springframework.http.*;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController public class Api {
              @GetMapping("/x") public ResponseEntity<Item> get() { return null; }
            }
            """,
            "package com.example; public class Item {}");

    EndpointModel endpoint = endpoint(endpoints, "get");

    assertThat(endpoint.responseType().qualifiedName()).isEqualTo("com.example.Item");
    assertThat(endpoint.returnType().text()).isEqualTo("ResponseEntity<Item>");
  }

  @Test
  @DisplayName("A ResponseEntity from an unrelated on-demand import is not removed")
  void keepsResponseEntityFromUnrelatedImport() {
    List<EndpointModel> endpoints =
        analyze(
            """
            package com.example;
            import com.other.*;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController public class Api {
              @GetMapping("/x") public ResponseEntity<Item> get() { return null; }
            }
            """,
            "package com.example; public class Item {}");

    assertThat(endpoint(endpoints, "get").responseType().text()).isEqualTo("ResponseEntity<Item>");
  }

  @Test
  @DisplayName("A project class named ResponseEntity shadows the on-demand import and is kept")
  void projectResponseEntityShadowsOnDemandImport() {
    List<EndpointModel> endpoints =
        analyze(
            """
            package com.example;
            import org.springframework.http.*;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController public class Api {
              @GetMapping("/x") public ResponseEntity<Item> get() { return null; }
            }
            """,
            "package com.example; public class Item {}",
            "package com.example; public class ResponseEntity<T> {}");

    assertThat(endpoint(endpoints, "get").responseType().qualifiedName())
        .isEqualTo("com.example.ResponseEntity");
  }

  private static final String USER_CONTROLLER =
      """
      package com.example.web;
      import org.springframework.http.ResponseEntity;
      import org.springframework.web.bind.annotation.*;
      import java.util.List;
      @RestController
      @RequestMapping("/api/users")
      class UserController {
        @GetMapping
        List<UserResponse> list() { return null; }
        @GetMapping("/{id}")
        ResponseEntity<UserResponse> get(@PathVariable Long id) { return null; }
        @PostMapping(path = "/")
        ResponseEntity<UserResponse> create(@RequestBody CreateUserRequest request) { return null; }
        @PutMapping(value = {"/{id}", "/{id}/replace"})
        void update(@PathVariable("id") Long id, @RequestBody UpdateUserRequest request) {}
        @DeleteMapping("/{id}")
        void delete(@PathVariable Long id) {}
        @PatchMapping("{id}")
        void patch(@PathVariable Long id) {}
        @RequestMapping(value = "/search", method = {RequestMethod.GET, RequestMethod.POST})
        List<UserResponse> search(@RequestParam String q) { return null; }
        @RequestMapping("/any")
        String any() { return null; }
        void helper() {}
      }
      """;

  @Test
  @DisplayName("Combines the type-level prefix with GET mapping without a path")
  void getWithoutMethodPath() {
    EndpointModel list = endpoint(analyze(USER_CONTROLLER), "list");

    assertThat(list.controllerType()).isEqualTo("com.example.web.UserController");
    assertThat(list.httpMethods()).containsExactly("GET");
    assertThat(list.paths()).containsExactly("/api/users");
    assertThat(list.pathsResolved()).isTrue();
    assertThat(list.requestBodyType()).isNull();
    assertThat(list.responseType().text()).isEqualTo("List<UserResponse>");
  }

  @Test
  @DisplayName(
      "Unwraps ResponseEntity<T> into the response type and keeps the declared return type")
  void unwrapsResponseEntity() {
    EndpointModel get = endpoint(analyze(USER_CONTROLLER), "get");

    assertThat(get.paths()).containsExactly("/api/users/{id}");
    assertThat(get.returnType().text()).isEqualTo("ResponseEntity<UserResponse>");
    assertThat(get.responseType().text()).isEqualTo("UserResponse");
    assertThat(get.parameters()).extracting(p -> p.name()).containsExactly("id");
  }

  @Test
  @DisplayName(
      "POST with @RequestBody records the request DTO; trailing '/' path is kept as written")
  void postWithRequestBody() {
    EndpointModel create = endpoint(analyze(USER_CONTROLLER), "create");

    assertThat(create.httpMethods()).containsExactly("POST");
    assertThat(create.paths()).containsExactly("/api/users/");
    assertThat(create.requestBodyType().text()).isEqualTo("CreateUserRequest");
    assertThat(create.responseType().text()).isEqualTo("UserResponse");
  }

  @Test
  @DisplayName("Multiple paths, PUT/DELETE/PATCH, and a method path without leading slash")
  void multiplePathsAndOtherVerbs() {
    List<EndpointModel> endpoints = analyze(USER_CONTROLLER);

    EndpointModel update = endpoint(endpoints, "update");
    assertThat(update.httpMethods()).containsExactly("PUT");
    assertThat(update.paths()).containsExactly("/api/users/{id}", "/api/users/{id}/replace");
    assertThat(update.requestBodyType().text()).isEqualTo("UpdateUserRequest");
    assertThat(update.responseType().text()).isEqualTo("void");
    assertThat(endpoint(endpoints, "delete").httpMethods()).containsExactly("DELETE");
    assertThat(endpoint(endpoints, "patch").paths()).containsExactly("/api/users/{id}");
    assertThat(endpoint(endpoints, "patch").httpMethods()).containsExactly("PATCH");
  }

  @Test
  @DisplayName(
      "@RequestMapping with method attribute lists methods; without it, accepts any (empty)")
  void requestMappingMethods() {
    List<EndpointModel> endpoints = analyze(USER_CONTROLLER);

    assertThat(endpoint(endpoints, "search").httpMethods()).containsExactly("GET", "POST");
    assertThat(endpoint(endpoints, "any").httpMethods()).isEmpty();
    assertThat(endpoints).extracting(EndpointModel::methodName).doesNotContain("helper");
    assertThat(endpoints).hasSize(8);
  }

  @Test
  @DisplayName(
      "A @Controller without type-level mapping uses the method path as is ('/' when empty)")
  void controllerWithoutTypeLevelMapping() {
    List<EndpointModel> endpoints =
        analyze(
            """
            package com.example.web;
            import org.springframework.stereotype.Controller;
            import org.springframework.web.bind.annotation.GetMapping;
            @Controller
            class HomeController {
              @GetMapping String home() { return "index"; }
              @GetMapping("about") String about() { return "about"; }
            }
            """);

    assertThat(endpoint(endpoints, "home").paths()).containsExactly("/");
    assertThat(endpoint(endpoints, "about").paths()).containsExactly("/about");
  }

  @Test
  @DisplayName("A path given by a constant is kept as raw text and marked unresolved")
  void constantPathIsUnresolved() {
    EndpointModel endpoint =
        analyze(
                """
                package com.example.web;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping(ApiPaths.ORDERS)
                class OrderController {
                  @GetMapping("/{id}") Object get() { return null; }
                }
                """)
            .get(0);

    assertThat(endpoint.pathsResolved()).isFalse();
    assertThat(endpoint.paths()).containsExactly("ApiPaths.ORDERS/{id}");
  }

  @Test
  @DisplayName("Mappings in a class that is not a controller are ignored")
  void ignoresNonControllers() {
    List<EndpointModel> endpoints =
        analyze(
            """
            package com.example.web;
            import org.springframework.web.bind.annotation.GetMapping;
            class NotAController {
              @GetMapping("/x") String x() { return null; }
            }
            """);

    assertThat(endpoints).isEmpty();
  }

  @Test
  @DisplayName("Spring Boot 2 style controller with fully qualified annotations is detected")
  void fullyQualifiedAnnotations() {
    List<EndpointModel> endpoints =
        analyze(
            """
            package com.example.web;
            @org.springframework.web.bind.annotation.RestController
            class PingController {
              @org.springframework.web.bind.annotation.GetMapping("/ping") String ping() { return "pong"; }
            }
            """);

    assertThat(endpoints)
        .singleElement()
        .satisfies(e -> assertThat(e.paths()).containsExactly("/ping"));
  }

  @Test
  @DisplayName(
      "Class-level request methods are combined with the method's, class first, like Spring")
  void classLevelRequestMethodsAreCombined() {
    List<EndpointModel> endpoints =
        analyze(
            """
            package com.example.web;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping(path = "/cm", method = RequestMethod.POST)
            class CommandController {
              @RequestMapping("/do") void plain() {}
              @GetMapping("/get") void get() {}
              @RequestMapping(value = "/both", method = {RequestMethod.POST, RequestMethod.PUT})
              void both() {}
            }
            """);

    assertThat(endpoint(endpoints, "plain").httpMethods()).containsExactly("POST");
    assertThat(endpoint(endpoints, "get").httpMethods()).containsExactly("POST", "GET");
    assertThat(endpoint(endpoints, "both").httpMethods()).containsExactly("POST", "PUT");
  }

  @Test
  @DisplayName(
      "Without class-level request methods, a plain @RequestMapping method matches every method")
  void noClassLevelRequestMethodsKeepsMethodLevel() {
    List<EndpointModel> endpoints =
        analyze(
            """
            package com.example.web;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/cm")
            class CommandController {
              @RequestMapping("/do") void plain() {}
            }
            """);

    assertThat(endpoint(endpoints, "plain").httpMethods()).isEmpty();
  }
}
