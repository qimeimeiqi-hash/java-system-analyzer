package com.example.javaanalyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.example.javaanalyzer.analysis.plugin.springboot.SpringBootPlugin;
import com.example.javaanalyzer.ir.CallModel;
import com.example.javaanalyzer.ir.DependencyResolution;
import com.example.javaanalyzer.ir.ProjectModel;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CallGraphExtractorTest {

  private static final String CALLER = "com.example.Caller";
  private static final String REPOSITORY_SOURCE =
      """
      package com.example;
      import org.springframework.stereotype.Component;
      @Component public class Repo {
        public void save(Object value) {}
        public Object load() { return null; }
      }
      """;

  @TempDir Path projectRoot;

  private ProjectModel analyze(String... sources) {
    for (String source : sources) {
      AnalysisTestSupport.writeSource(projectRoot, source);
    }
    return new ProjectAnalyzer(List.of(new SpringBootPlugin())).analyze(projectRoot);
  }

  /** A service with a field-injected {@code Repo repo} and the given class body. */
  private List<CallModel> callsIn(String body) {
    return analyze(
            """
            package com.example;
            import java.util.List;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;
            @Service public class Caller {
              @Autowired Repo repo;
              %s
            }
            """
                .formatted(body),
            REPOSITORY_SOURCE)
        .calls();
  }

  @Test
  @DisplayName("A call on an injected field records the caller, callee and resolved target")
  void detectsCallOnField() {
    List<CallModel> calls = callsIn("void run(String id, int count) { repo.save(id); }");

    assertThat(calls).hasSize(1);
    CallModel call = calls.get(0);
    assertThat(call.fromType()).isEqualTo(CALLER);
    assertThat(call.fromMethod()).isEqualTo("run");
    assertThat(call.fromParameterTypes()).containsExactly("String", "int");
    assertThat(call.fieldName()).isEqualTo("repo");
    assertThat(call.methodName()).isEqualTo("save");
    assertThat(call.methodReference()).isFalse();
    assertThat(call.argumentCount()).isEqualTo(1);
    assertThat(call.declaredType().text()).isEqualTo("Repo");
    assertThat(call.targetType()).isEqualTo("com.example.Repo");
    assertThat(call.resolution()).isEqualTo(DependencyResolution.PROJECT_TYPE);
    assertThat(call.line()).isEqualTo(7);
  }

  @Test
  @DisplayName("this.field calls are detected, and the argument count of no arguments is zero")
  void detectsThisQualifiedCall() {
    List<CallModel> calls = callsIn("void run() { this.repo.load(); }");

    assertThat(calls).extracting(CallModel::methodName).containsExactly("load");
    assertThat(calls.get(0).argumentCount()).isZero();
  }

  @Test
  @DisplayName("this.field is detected even when a local variable has the same name")
  void thisQualifiedCallIgnoresLocalVariable() {
    assertThat(callsIn("void run(String repo) { this.repo.load(); }")).hasSize(1);
  }

  @Test
  @DisplayName("Overloads of the caller are told apart by their parameter types")
  void separatesCallerOverloads() {
    assertThat(callsIn("void run() { repo.load(); } void run(int a) { repo.load(); }"))
        .extracting(CallModel::fromParameterTypes)
        .containsExactly(List.of(), List.of("int"));
  }

  @Test
  @DisplayName("Calls inside a lambda are detected")
  void detectsCallInLambda() {
    assertThat(callsIn("void run(List<String> ids) { ids.forEach(id -> repo.save(id)); }"))
        .extracting(CallModel::methodName)
        .containsExactly("save");
  }

  @Test
  @DisplayName("Only the first call of a chain is on the field")
  void detectsOnlyFirstCallOfChain() {
    assertThat(callsIn("void run() { repo.load().toString(); }"))
        .extracting(CallModel::methodName)
        .containsExactly("load");
  }

  @Test
  @DisplayName("A method reference on the field is detected and marked, without argument count")
  void detectsMethodReference() {
    List<CallModel> calls = callsIn("void run(List<String> ids) { ids.forEach(repo::save); }");

    assertThat(calls).extracting(CallModel::methodName).containsExactly("save");
    assertThat(calls.get(0).methodReference()).isTrue();
    assertThat(calls.get(0).argumentCount()).isNull();
  }

  @Test
  @DisplayName("A method reference through this is detected")
  void detectsThisQualifiedMethodReference() {
    assertThat(callsIn("void run(List<String> ids) { ids.forEach(this.repo::save); }"))
        .extracting(CallModel::methodName)
        .containsExactly("save");
  }

  @Test
  @DisplayName("A method reference on a class with the name of no field is not a call on a field")
  void ignoresMethodReferenceOnClass() {
    assertThat(callsIn("void run(List<String> ids) { ids.forEach(String::length); }")).isEmpty();
  }

  @Test
  @DisplayName("Calls are in source order, across calls and method references")
  void keepsSourceOrder() {
    assertThat(
            callsIn(
                "void run(List<String> ids) { repo.load(); ids.forEach(repo::save); repo.load(); }"))
        .extracting(CallModel::methodName)
        .containsExactly("load", "save", "load");
  }

  @Test
  @DisplayName("A parameter with the name of the field shadows it")
  void parameterShadowsField() {
    assertThat(callsIn("void run(Object repo) { repo.toString(); }")).isEmpty();
  }

  @Test
  @DisplayName("A local variable with the name of the field shadows it after its declaration")
  void localVariableShadowsFieldAfterDeclaration() {
    assertThat(callsIn("void run() { repo.load(); Object repo = null; repo.toString(); }"))
        .extracting(CallModel::methodName)
        .containsExactly("load");
  }

  @Test
  @DisplayName("A local variable of a finished block does not shadow the field afterwards")
  void finishedBlockDoesNotShadow() {
    assertThat(callsIn("void run() { { Object repo = null; repo.toString(); } repo.load(); }"))
        .extracting(CallModel::methodName)
        .containsExactly("load");
  }

  @Test
  @DisplayName("A lambda parameter shadows the field only inside the lambda")
  void lambdaParameterShadowsOnlyInside() {
    assertThat(
            callsIn(
                "void run(List<String> ids) { ids.forEach(repo -> repo.length()); repo.load(); }"))
        .extracting(CallModel::methodName)
        .containsExactly("load");
  }

  @Test
  @DisplayName("A for-each variable shadows the field only inside the loop")
  void forEachVariableShadowsOnlyInside() {
    assertThat(
            callsIn(
                "void run(List<String> ids) { for (String repo : ids) { repo.length(); } repo.load(); }"))
        .extracting(CallModel::methodName)
        .containsExactly("load");
  }

  @Test
  @DisplayName("A for variable shadows the field only inside the loop")
  void forVariableShadowsOnlyInside() {
    assertThat(
            callsIn(
                "void run() { for (Object repo = null; ; repo.hashCode()) { break; } repo.load(); }"))
        .extracting(CallModel::methodName)
        .containsExactly("load");
  }

  @Test
  @DisplayName("A caught exception variable shadows the field only inside the catch block")
  void catchParameterShadowsOnlyInside() {
    assertThat(
            callsIn(
                "void run() { try { repo.load(); } catch (RuntimeException repo) { repo.getCause(); } }"))
        .extracting(CallModel::methodName)
        .containsExactly("load");
  }

  @Test
  @DisplayName("A try resource shadows the field inside the try statement")
  void tryResourceShadowsField() {
    assertThat(
            callsIn(
                "void run() throws Exception { try (java.io.StringReader repo = null) { repo.read(); } repo.load(); }"))
        .extracting(CallModel::methodName)
        .containsExactly("load");
  }

  @Test
  @DisplayName("A pattern variable shadows the field in its statement")
  void patternVariableShadowsField() {
    assertThat(
            callsIn(
                "void run(Object o) { if (o instanceof String repo) { repo.length(); } repo.load(); }"))
        .extracting(CallModel::methodName)
        .containsExactly("load");
  }

  @Test
  @DisplayName("A field that is not injected is not part of the call graph")
  void ignoresFieldsThatAreNotInjected() {
    assertThat(callsIn("private final StringBuilder text = null; void run() { text.length(); }"))
        .isEmpty();
  }

  @Test
  @DisplayName("Calls in constructors are out of scope")
  void ignoresCallsInConstructors() {
    assertThat(callsIn("Caller() { repo.load(); }")).isEmpty();
  }

  @Test
  @DisplayName("A Lombok constructor injection is matched to the field of the same name")
  void detectsCallThroughLombokConstructor() {
    ProjectModel model =
        analyze(
            """
            package com.example;
            import lombok.RequiredArgsConstructor;
            import org.springframework.stereotype.Service;
            @Service @RequiredArgsConstructor public class Caller {
              private final Repo repo;
              void run() { repo.load(); }
            }
            """,
            REPOSITORY_SOURCE);

    assertThat(model.calls())
        .extracting(CallModel::fieldName, CallModel::targetType)
        .containsExactly(tuple("repo", "com.example.Repo"));
  }

  @Test
  @DisplayName("A constructor parameter named differently is matched through its assignment")
  void matchesConstructorParameterThroughAssignment() {
    ProjectModel model =
        analyze(
            """
            package com.example;
            import org.springframework.stereotype.Service;
            @Service public class Caller {
              private final Repo store;
              public Caller(Repo repository) { this.store = repository; }
              void run() { store.load(); }
            }
            """,
            REPOSITORY_SOURCE);

    assertThat(model.calls())
        .extracting(CallModel::fieldName, CallModel::targetType)
        .containsExactly(tuple("store", "com.example.Repo"));
  }

  @Test
  @DisplayName("An assignment without this is matched too")
  void matchesAssignmentWithoutThis() {
    ProjectModel model =
        analyze(
            """
            package com.example;
            import org.springframework.stereotype.Service;
            @Service public class Caller {
              private final Repo store;
              public Caller(Repo repository) { store = repository; }
              void run() { store.load(); }
            }
            """,
            REPOSITORY_SOURCE);

    assertThat(model.calls()).extracting(CallModel::fieldName).containsExactly("store");
  }

  @Test
  @DisplayName("A setter parameter named differently is matched through its assignment")
  void matchesSetterParameterThroughAssignment() {
    ProjectModel model =
        analyze(
            """
            package com.example;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;
            @Service public class Caller {
              private Repo store;
              @Autowired public void setStore(Repo repository) { this.store = repository; }
              void run() { store.load(); }
            }
            """,
            REPOSITORY_SOURCE);

    assertThat(model.calls()).extracting(CallModel::fieldName).containsExactly("store");
  }

  @Test
  @DisplayName("An interface with one implementation reuses the resolved dependency as the target")
  void reusesSingleImplementationResolution() {
    ProjectModel model =
        analyze(
            """
            package com.example;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;
            @Service public class Caller {
              @Autowired Gateway gateway;
              void run() { gateway.send(); }
            }
            """,
            "package com.example; public interface Gateway { void send(); }",
            """
            package com.example;
            import org.springframework.stereotype.Component;
            @Component public class HttpGateway implements Gateway {
              public void send() {}
            }
            """);

    CallModel call = model.calls().get(0);
    assertThat(call.declaredType().qualifiedName()).isEqualTo("com.example.Gateway");
    assertThat(call.targetType()).isEqualTo("com.example.HttpGateway");
    assertThat(call.resolution()).isEqualTo(DependencyResolution.SINGLE_IMPLEMENTATION);
  }

  @Test
  @DisplayName("An ambiguous dependency keeps the call visible without choosing a target")
  void keepsAmbiguousCallWithoutTarget() {
    ProjectModel model =
        analyze(
            """
            package com.example;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;
            @Service public class Caller {
              @Autowired Gateway gateway;
              void run() { gateway.send(); }
            }
            """,
            "package com.example; public interface Gateway { void send(); }",
            """
            package com.example;
            import org.springframework.stereotype.Component;
            @Component public class FirstGateway implements Gateway { public void send() {} }
            """,
            """
            package com.example;
            import org.springframework.stereotype.Component;
            @Component public class SecondGateway implements Gateway { public void send() {} }
            """);

    CallModel call = model.calls().get(0);
    assertThat(call.targetType()).isNull();
    assertThat(call.resolution()).isEqualTo(DependencyResolution.AMBIGUOUS);
  }

  @Test
  @DisplayName("An unresolved dependency keeps the call visible without a target")
  void keepsUnresolvedCallWithoutTarget() {
    ProjectModel model =
        analyze(
            """
            package com.example;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;
            @Service public class Caller {
              @Autowired NoSuchType thing;
              void run() { thing.work(); }
            }
            """);

    CallModel call = model.calls().get(0);
    assertThat(call.targetType()).isNull();
    assertThat(call.resolution()).isEqualTo(DependencyResolution.UNRESOLVED);
  }

  @Test
  @DisplayName("Calls on library types are out of scope")
  void ignoresCallsOnExternalDependencies() {
    ProjectModel model =
        analyze(
            """
            package com.example;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;
            import org.springframework.web.client.RestTemplate;
            @Service public class Caller {
              @Autowired RestTemplate client;
              void run() { client.getForObject("x", String.class); }
            }
            """);

    assertThat(model.calls()).isEmpty();
  }

  @Test
  @DisplayName("Calls on a List field act on the container and are not recorded")
  void ignoresCallsOnCollectionField() {
    ProjectModel model =
        analyze(
            """
            package com.example;
            import java.util.List;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;
            @Service public class Caller {
              @Autowired List<Repo> repos;
              void run() { repos.size(); }
            }
            """,
            REPOSITORY_SOURCE);

    assertThat(model.calls()).isEmpty();
  }

  @Test
  @DisplayName("Calls on an Optional field act on the wrapper and are not recorded")
  void ignoresCallsOnOptionalField() {
    ProjectModel model =
        analyze(
            """
            package com.example;
            import java.util.Optional;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;
            @Service public class Caller {
              @Autowired Optional<Repo> repo;
              void run() { repo.isPresent(); }
            }
            """,
            REPOSITORY_SOURCE);

    assertThat(model.calls()).isEmpty();
  }

  @Test
  @DisplayName("A class that is not a component has no recorded calls")
  void ignoresTypesThatAreNotComponents() {
    ProjectModel model =
        analyze(
            """
            package com.example;
            public class Plain {
              private Repo repo;
              void run() { repo.load(); }
            }
            """,
            REPOSITORY_SOURCE);

    assertThat(model.calls()).isEmpty();
  }

  @Test
  @DisplayName("Calls of a @Configuration class are recorded")
  void detectsCallsInConfigurationClass() {
    ProjectModel model =
        analyze(
            """
            package com.example;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            @Configuration public class AppConfig {
              @Autowired Repo repo;
              @Bean public Object value() { return repo.load(); }
            }
            """,
            REPOSITORY_SOURCE);

    assertThat(model.calls())
        .extracting(CallModel::fromType, CallModel::fromMethod)
        .containsExactly(tuple("com.example.AppConfig", "value"));
  }

  @Test
  @DisplayName("Calls are in package order so that they match what is read back from JSON")
  void callsAreInPackageOrder() {
    ProjectModel model =
        analyze(
            """
            package com.example.b;
            import com.example.Repo;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;
            @Service public class Second {
              @Autowired Repo repo;
              void run() { repo.load(); }
            }
            """,
            """
            package com.example.a;
            import com.example.Repo;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;
            @Service public class First {
              @Autowired Repo repo;
              void run() { repo.load(); }
            }
            """,
            REPOSITORY_SOURCE);

    assertThat(model.calls())
        .extracting(CallModel::fromType)
        .containsExactly("com.example.a.First", "com.example.b.Second");
  }

  @Test
  @DisplayName(
      "Setters with the same parameter name are told apart by their own assignment and type")
  void settersWithSameParameterNameKeepTheirOwnField() {
    List<CallModel> calls =
        analyze(
                """
                package com.example;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.stereotype.Service;
                @Service public class Caller {
                  private Repo userRepo;
                  private OrderRepo orderRepo;
                  @Autowired void setUserRepo(Repo repo) { this.userRepo = repo; }
                  @Autowired void setOrderRepo(OrderRepo repo) { this.orderRepo = repo; }
                  void run() { userRepo.load(); orderRepo.find(); }
                }
                """,
                REPOSITORY_SOURCE,
                """
                package com.example;
                import org.springframework.stereotype.Component;
                @Component public class OrderRepo { public Object find() { return null; } }
                """)
            .calls();

    assertThat(calls)
        .extracting(
            CallModel::fieldName,
            CallModel::methodName,
            c -> c.declaredType().text(),
            CallModel::targetType)
        .containsExactly(
            tuple("userRepo", "load", "Repo", "com.example.Repo"),
            tuple("orderRepo", "find", "OrderRepo", "com.example.OrderRepo"));
  }

  @Test
  @DisplayName("Two setters with the same parameter name and type each keep their own field")
  void settersWithSameParameterNameAndTypeKeepTheirOwnField() {
    List<CallModel> calls =
        analyze(
                """
                package com.example;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.stereotype.Service;
                @Service public class Caller {
                  private Repo primary;
                  private Repo secondary;
                  @Autowired void setPrimary(Repo repo) { this.primary = repo; }
                  @Autowired void setSecondary(Repo repo) { this.secondary = repo; }
                  void run() { primary.load(); secondary.save(null); }
                }
                """,
                REPOSITORY_SOURCE)
            .calls();

    assertThat(calls)
        .extracting(CallModel::fieldName, CallModel::methodName)
        .containsExactly(tuple("primary", "load"), tuple("secondary", "save"));
  }
}
