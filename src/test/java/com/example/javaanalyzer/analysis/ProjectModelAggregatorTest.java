package com.example.javaanalyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.example.javaanalyzer.analysis.plugin.springboot.SpringBootPlugin;
import com.example.javaanalyzer.ir.AnalysisIssue;
import com.example.javaanalyzer.ir.DependencyModel;
import com.example.javaanalyzer.ir.DependencyResolution;
import com.example.javaanalyzer.ir.InjectionKind;
import com.example.javaanalyzer.ir.IssueKind;
import com.example.javaanalyzer.ir.IssueSeverity;
import com.example.javaanalyzer.ir.PackageModel;
import com.example.javaanalyzer.ir.ProjectModel;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectModelAggregatorTest {

  @TempDir Path projectRoot;

  private ProjectModel analyze(String... sources) {
    for (String source : sources) {
      AnalysisTestSupport.writeSource(projectRoot, source);
    }
    return new ProjectAnalyzer(List.of(new SpringBootPlugin())).analyze(projectRoot);
  }

  private static String service(String pkg, String name, String... injectedFields) {
    StringBuilder fields = new StringBuilder();
    for (String field : injectedFields) {
      fields.append("  @Autowired ").append(field).append(";\n");
    }
    return """
        package %s;
        import org.springframework.stereotype.Service;
        import org.springframework.beans.factory.annotation.Autowired;
        @Service
        public class %s {
        %s}
        """
        .formatted(pkg, name, fields);
  }

  private static DependencyModel dependency(ProjectModel model, String fromType, String member) {
    return model.dependencies().stream()
        .filter(d -> d.fromType().equals(fromType) && d.memberName().equals(member))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No dependency " + fromType + "." + member));
  }

  private static PackageModel packageNamed(ProjectModel model, String name) {
    return model.packages().stream()
        .filter(p -> p.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No package " + name));
  }

  private static List<AnalysisIssue> cycles(ProjectModel model) {
    return model.issues().stream().filter(i -> i.kind() == IssueKind.CIRCULAR_DEPENDENCY).toList();
  }

  @Test
  @DisplayName("Injection of a concrete project class resolves to that class as PROJECT_TYPE")
  void concreteClassIsProjectType() {
    ProjectModel model =
        analyze(
            service("com.example", "Caller", "Target target"), service("com.example", "Target"));

    DependencyModel dependency = dependency(model, "com.example.Caller", "target");

    assertThat(dependency.resolution()).isEqualTo(DependencyResolution.PROJECT_TYPE);
    assertThat(dependency.resolvedType()).isEqualTo("com.example.Target");
    assertThat(dependency.declaredType().text()).isEqualTo("Target");
    assertThat(dependency.injectionKind()).isEqualTo(InjectionKind.FIELD);
  }

  @Test
  @DisplayName("A Spring Data repository without @Repository is still a dependency target")
  void springDataRepositoryIsResolvedTarget() {
    ProjectModel model =
        analyze(
            service("com.example.service", "OrderService", "OrderRepository orders")
                .replace(
                    "import org.springframework.stereotype.Service;",
                    "import org.springframework.stereotype.Service;\n"
                        + "import com.example.repo.OrderRepository;"),
            """
            package com.example.repo;
            import org.springframework.data.jpa.repository.JpaRepository;
            public interface OrderRepository extends JpaRepository<Object, Long> {}
            """);

    DependencyModel dependency = dependency(model, "com.example.service.OrderService", "orders");

    assertThat(dependency.resolution()).isEqualTo(DependencyResolution.PROJECT_TYPE);
    assertThat(dependency.resolvedType()).isEqualTo("com.example.repo.OrderRepository");
    assertThat(packageNamed(model, "com.example.service").dependsOn())
        .containsExactly("com.example.repo");
  }

  @Test
  @DisplayName("Interface with exactly one implementing component is SINGLE_IMPLEMENTATION")
  void singleImplementationIsResolved() {
    ProjectModel model =
        analyze(
            service("com.example", "Caller", "Gateway gateway"),
            "package com.example; public interface Gateway {}",
            """
            package com.example;
            import org.springframework.stereotype.Component;
            @Component public class HttpGateway implements Gateway {}
            """);

    DependencyModel dependency = dependency(model, "com.example.Caller", "gateway");

    assertThat(dependency.resolution()).isEqualTo(DependencyResolution.SINGLE_IMPLEMENTATION);
    assertThat(dependency.resolvedType()).isEqualTo("com.example.HttpGateway");
    assertThat(dependency.declaredType().qualifiedName()).isEqualTo("com.example.Gateway");
  }

  @Test
  @DisplayName("An implementation reached through an abstract base class is found")
  void implementationThroughAbstractBaseIsFound() {
    ProjectModel model =
        analyze(
            service("com.example", "Caller", "Gateway gateway"),
            "package com.example; public interface Gateway {}",
            "package com.example; public abstract class BaseGateway implements Gateway {}",
            """
            package com.example;
            import org.springframework.stereotype.Component;
            @Component public class HttpGateway extends BaseGateway {}
            """);

    assertThat(dependency(model, "com.example.Caller", "gateway").resolvedType())
        .isEqualTo("com.example.HttpGateway");
  }

  @Test
  @DisplayName("Injection of an abstract class with one component subclass resolves to it")
  void abstractClassWithOneSubclassIsResolved() {
    ProjectModel model =
        analyze(
            service("com.example", "Caller", "BaseGateway gateway"),
            "package com.example; public abstract class BaseGateway {}",
            """
            package com.example;
            import org.springframework.stereotype.Component;
            @Component public class HttpGateway extends BaseGateway {}
            """);

    DependencyModel dependency = dependency(model, "com.example.Caller", "gateway");

    assertThat(dependency.resolution()).isEqualTo(DependencyResolution.SINGLE_IMPLEMENTATION);
    assertThat(dependency.resolvedType()).isEqualTo("com.example.HttpGateway");
  }

  @Test
  @DisplayName("Implementations that are not components do not count as candidates")
  void nonComponentImplementationIsIgnored() {
    ProjectModel model =
        analyze(
            service("com.example", "Caller", "Gateway gateway"),
            "package com.example; public interface Gateway {}",
            "package com.example; public class PlainGateway implements Gateway {}",
            """
            package com.example;
            import org.springframework.stereotype.Component;
            @Component public class HttpGateway implements Gateway {}
            """);

    assertThat(dependency(model, "com.example.Caller", "gateway").resolvedType())
        .isEqualTo("com.example.HttpGateway");
  }

  @Test
  @DisplayName("Interface with several implementing components is AMBIGUOUS without a target")
  void severalImplementationsAreAmbiguous() {
    ProjectModel model =
        analyze(
            service("com.example", "Caller", "Gateway gateway"),
            "package com.example; public interface Gateway {}",
            """
            package com.example;
            import org.springframework.stereotype.Component;
            @Component public class HttpGateway implements Gateway {}
            """,
            """
            package com.example;
            import org.springframework.stereotype.Component;
            @Component public class MockGateway implements Gateway {}
            """);

    DependencyModel dependency = dependency(model, "com.example.Caller", "gateway");

    assertThat(dependency.resolution()).isEqualTo(DependencyResolution.AMBIGUOUS);
    assertThat(dependency.resolvedType()).isNull();
  }

  @Test
  @DisplayName("Project interface without any implementation stays a dependency on the interface")
  void interfaceWithoutImplementationIsKept() {
    ProjectModel model =
        analyze(
            service("com.example", "Caller", "OrderMapper mapper"),
            "package com.example; public interface OrderMapper {}");

    DependencyModel dependency = dependency(model, "com.example.Caller", "mapper");

    assertThat(dependency.resolution()).isEqualTo(DependencyResolution.PROJECT_TYPE);
    assertThat(dependency.resolvedType()).isEqualTo("com.example.OrderMapper");
  }

  @Test
  @DisplayName("A type outside the project is EXTERNAL with no target, keeping the declared type")
  void externalTypeHasNoTarget() {
    ProjectModel model =
        analyze(
            service("com.example", "Caller", "RestTemplate client")
                .replace(
                    "import org.springframework.stereotype.Service;",
                    "import org.springframework.stereotype.Service;\n"
                        + "import org.springframework.web.client.RestTemplate;"));

    DependencyModel dependency = dependency(model, "com.example.Caller", "client");

    assertThat(dependency.resolution()).isEqualTo(DependencyResolution.EXTERNAL);
    assertThat(dependency.resolvedType()).isNull();
    assertThat(dependency.declaredType().qualifiedName())
        .isEqualTo("org.springframework.web.client.RestTemplate");
  }

  @Test
  @DisplayName("A type that cannot be determined is UNRESOLVED")
  void undeterminedTypeIsUnresolved() {
    ProjectModel model = analyze(service("com.example", "Caller", "NoSuchType missing"));

    DependencyModel dependency = dependency(model, "com.example.Caller", "missing");

    assertThat(dependency.resolution()).isEqualTo(DependencyResolution.UNRESOLVED);
    assertThat(dependency.resolvedType()).isNull();
  }

  @Test
  @DisplayName("A project class whose source failed to parse is UNRESOLVED, never EXTERNAL")
  void typeFromBrokenSourceIsUnresolved() {
    ProjectModel model =
        analyze(
            service("com.example.app", "Caller", "Broken broken")
                .replace(
                    "import org.springframework.stereotype.Service;",
                    "import org.springframework.stereotype.Service;\n"
                        + "import com.example.lib.Broken;"),
            "package com.example.lib; public class Broken { void oops( }");

    DependencyModel dependency = dependency(model, "com.example.app.Caller", "broken");

    assertThat(dependency.resolution()).isEqualTo(DependencyResolution.UNRESOLVED);
    assertThat(dependency.resolvedType()).isNull();
  }

  @Test
  @DisplayName("Lombok @RequiredArgsConstructor injection appears as a dependency")
  void lombokConstructorInjectionIsCaptured() {
    ProjectModel model =
        analyze(
            """
            package com.example;
            import lombok.RequiredArgsConstructor;
            import org.springframework.stereotype.Service;
            @Service
            @RequiredArgsConstructor
            public class Caller {
              private final Target target;
            }
            """,
            service("com.example", "Target"));

    DependencyModel dependency = dependency(model, "com.example.Caller", "target");

    assertThat(dependency.injectionKind()).isEqualTo(InjectionKind.CONSTRUCTOR);
    assertThat(dependency.resolvedType()).isEqualTo("com.example.Target");
  }

  @Test
  @DisplayName("Packages list their types and depend on the package of the resolved implementation")
  void packagesAggregateResolvedDependencies() {
    ProjectModel model =
        analyze(
            """
            package com.example.web;
            import com.example.service.OrderService;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Controller;
            @Controller
            public class OrderController {
              @Autowired OrderService orders;
              @Autowired Helper helper;
            }
            """,
            "package com.example.web; import org.springframework.stereotype.Component;"
                + " @Component public class Helper {}",
            "package com.example.service; public interface OrderService {}",
            """
            package com.example.service.impl;
            import com.example.service.OrderService;
            import org.springframework.stereotype.Service;
            @Service public class OrderServiceImpl implements OrderService {}
            """);

    assertThat(model.packages())
        .extracting(PackageModel::name)
        .containsExactly("com.example.service", "com.example.service.impl", "com.example.web");
    assertThat(packageNamed(model, "com.example.web").typeNames())
        .containsExactly("com.example.web.Helper", "com.example.web.OrderController");
    assertThat(packageNamed(model, "com.example.web").dependsOn())
        .as("the same-package dependency on Helper is not a package dependency")
        .containsExactly("com.example.service.impl");
    assertThat(packageNamed(model, "com.example.service").dependsOn()).isEmpty();
  }

  @Test
  @DisplayName("A project without cycles reports no CIRCULAR_DEPENDENCY issue")
  void noCycleNoIssue() {
    ProjectModel model =
        analyze(
            service("com.example", "Caller", "Target target"), service("com.example", "Target"));

    assertThat(cycles(model)).isEmpty();
  }

  @Test
  @DisplayName("Two services injecting each other are reported once as a WARNING naming both")
  void reportsComponentCycle() {
    ProjectModel model =
        analyze(
            service("com.example", "Alpha", "Beta beta"),
            service("com.example", "Beta", "Alpha alpha"));

    assertThat(cycles(model)).hasSize(1);
    AnalysisIssue issue = cycles(model).get(0);
    assertThat(issue.severity()).isEqualTo(IssueSeverity.WARNING);
    assertThat(issue.file()).isNull();
    assertThat(issue.message()).contains("com.example.Alpha", "com.example.Beta");
  }

  @Test
  @DisplayName("A cycle closed through a single-implementation interface is detected")
  void reportsCycleThroughInterface() {
    ProjectModel model =
        analyze(
            service("com.example", "Alpha", "BetaApi beta"),
            "package com.example; public interface BetaApi {}",
            """
            package com.example;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;
            @Service public class BetaImpl implements BetaApi {
              @Autowired Alpha alpha;
            }
            """);

    assertThat(cycles(model)).hasSize(1);
    assertThat(cycles(model).get(0).message())
        .contains("com.example.Alpha", "com.example.BetaImpl");
  }

  @Test
  @DisplayName("A service injecting itself is reported as a cycle")
  void reportsSelfInjection() {
    ProjectModel model = analyze(service("com.example", "Alpha", "Alpha self"));

    assertThat(cycles(model)).hasSize(1);
    assertThat(cycles(model).get(0).message()).contains("com.example.Alpha");
  }

  @Test
  @DisplayName("An AMBIGUOUS dependency creates no edge, so it cannot close a cycle")
  void ambiguousDependencyDoesNotCloseCycle() {
    ProjectModel model =
        analyze(
            service("com.example", "Alpha", "BetaApi beta"),
            "package com.example; public interface BetaApi {}",
            """
            package com.example;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;
            @Service public class BetaOne implements BetaApi {
              @Autowired Alpha alpha;
            }
            """,
            """
            package com.example;
            import org.springframework.stereotype.Service;
            @Service public class BetaTwo implements BetaApi {}
            """);

    assertThat(cycles(model)).isEmpty();
  }

  @Test
  @DisplayName("Packages depending on each other are reported even when components have no cycle")
  void reportsPackageOnlyCycle() {
    ProjectModel model =
        analyze(
            """
            package com.example.a;
            import com.example.b.Y;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;
            @Service public class X {
              @Autowired Y y;
            }
            """,
            "package com.example.a; import org.springframework.stereotype.Service;"
                + " @Service public class W {}",
            "package com.example.b; import org.springframework.stereotype.Service;"
                + " @Service public class Y {}",
            """
            package com.example.b;
            import com.example.a.W;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;
            @Service public class Z {
              @Autowired W w;
            }
            """);

    assertThat(cycles(model)).hasSize(1);
    assertThat(cycles(model).get(0).message()).contains("com.example.a", "com.example.b");
  }

  private static final String LIST_IMPORT =
      "import org.springframework.stereotype.Service;\nimport java.util.List;";

  private static String handlerImpl(String name) {
    return """
        package com.example;
        import org.springframework.stereotype.Component;
        @Component public class %s implements Handler {}
        """
        .formatted(name);
  }

  private static List<DependencyModel> dependencies(
      ProjectModel model, String fromType, String member) {
    return model.dependencies().stream()
        .filter(d -> d.fromType().equals(fromType) && d.memberName().equals(member))
        .toList();
  }

  @Test
  @DisplayName("Optional of a project type resolves like a direct injection of that type")
  void optionalResolvesToWrappedType() {
    ProjectModel model =
        analyze(
            service("com.example", "Caller", "Optional<Target> target")
                .replace(
                    "import org.springframework.stereotype.Service;",
                    "import org.springframework.stereotype.Service;\n"
                        + "import java.util.Optional;"),
            service("com.example", "Target"));

    DependencyModel dependency = dependency(model, "com.example.Caller", "target");

    assertThat(dependency.resolution()).isEqualTo(DependencyResolution.PROJECT_TYPE);
    assertThat(dependency.resolvedType()).isEqualTo("com.example.Target");
    assertThat(dependency.declaredType().text()).isEqualTo("Optional<Target>");
  }

  @Test
  @DisplayName("List of an interface has one COLLECTION_ELEMENT entry per implementation")
  void listHasOneEntryPerImplementation() {
    ProjectModel model =
        analyze(
            service("com.example", "Caller", "List<Handler> handlers")
                .replace("import org.springframework.stereotype.Service;", LIST_IMPORT),
            "package com.example; public interface Handler {}",
            handlerImpl("FirstHandler"),
            handlerImpl("SecondHandler"));

    List<DependencyModel> handlers = dependencies(model, "com.example.Caller", "handlers");

    assertThat(handlers)
        .extracting(DependencyModel::resolvedType)
        .containsExactly("com.example.FirstHandler", "com.example.SecondHandler");
    assertThat(handlers)
        .allSatisfy(
            d -> {
              assertThat(d.resolution()).isEqualTo(DependencyResolution.COLLECTION_ELEMENT);
              assertThat(d.declaredType().text()).isEqualTo("List<Handler>");
            });
  }

  @Test
  @DisplayName("List with a single implementation is still a COLLECTION_ELEMENT entry")
  void listWithOneImplementation() {
    ProjectModel model =
        analyze(
            service("com.example", "Caller", "List<Handler> handlers")
                .replace("import org.springframework.stereotype.Service;", LIST_IMPORT),
            "package com.example; public interface Handler {}",
            handlerImpl("OnlyHandler"));

    DependencyModel dependency = dependency(model, "com.example.Caller", "handlers");

    assertThat(dependency.resolution()).isEqualTo(DependencyResolution.COLLECTION_ELEMENT);
    assertThat(dependency.resolvedType()).isEqualTo("com.example.OnlyHandler");
  }

  @Test
  @DisplayName("List of an interface without implementations stays on the interface")
  void listWithoutImplementationKeepsInterface() {
    ProjectModel model =
        analyze(
            service("com.example", "Caller", "List<Handler> handlers")
                .replace("import org.springframework.stereotype.Service;", LIST_IMPORT),
            "package com.example; public interface Handler {}");

    DependencyModel dependency = dependency(model, "com.example.Caller", "handlers");

    assertThat(dependency.resolution()).isEqualTo(DependencyResolution.PROJECT_TYPE);
    assertThat(dependency.resolvedType()).isEqualTo("com.example.Handler");
  }

  @Test
  @DisplayName("List of a library type stays EXTERNAL with no target")
  void listOfExternalTypeIsExternal() {
    ProjectModel model =
        analyze(
            service("com.example", "Caller", "List<String> names")
                .replace("import org.springframework.stereotype.Service;", LIST_IMPORT));

    DependencyModel dependency = dependency(model, "com.example.Caller", "names");

    assertThat(dependency.resolution()).isEqualTo(DependencyResolution.EXTERNAL);
    assertThat(dependency.resolvedType()).isNull();
  }

  @Test
  @DisplayName("A composite collecting its own interface does not depend on itself")
  void compositeExcludesItselfFromCollection() {
    ProjectModel model =
        analyze(
            """
            package com.example;
            import java.util.List;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Component;
            @Component public class CompositeHandler implements Handler {
              @Autowired List<Handler> delegates;
            }
            """,
            "package com.example; public interface Handler {}",
            handlerImpl("RealHandler"));

    assertThat(dependencies(model, "com.example.CompositeHandler", "delegates"))
        .extracting(DependencyModel::resolvedType)
        .containsExactly("com.example.RealHandler");
    assertThat(cycles(model)).isEmpty();
  }

  @Test
  @DisplayName("A cycle closed through a List injection is detected")
  void reportsCycleThroughList() {
    ProjectModel model =
        analyze(
            service("com.example", "Caller", "List<Handler> handlers")
                .replace("import org.springframework.stereotype.Service;", LIST_IMPORT),
            "package com.example; public interface Handler {}",
            """
            package com.example;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Component;
            @Component public class CallingHandler implements Handler {
              @Autowired Caller caller;
            }
            """);

    assertThat(cycles(model)).hasSize(1);
    assertThat(cycles(model).get(0).message())
        .contains("com.example.Caller", "com.example.CallingHandler");
  }

  @Test
  @DisplayName("A List injection across packages appears as a package dependency")
  void listCreatesPackageDependency() {
    ProjectModel model =
        analyze(
            """
            package com.example.app;
            import com.example.handler.Handler;
            import java.util.List;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;
            @Service public class Caller {
              @Autowired List<Handler> handlers;
            }
            """,
            "package com.example.handler; public interface Handler {}",
            """
            package com.example.handler.impl;
            import com.example.handler.Handler;
            import org.springframework.stereotype.Component;
            @Component public class RealHandler implements Handler {}
            """);

    assertThat(packageNamed(model, "com.example.app").dependsOn())
        .containsExactly("com.example.handler.impl");
  }

  private static String beanConfig(String returnType, String methodName) {
    return """
        package com.example;
        import org.springframework.context.annotation.Bean;
        import org.springframework.context.annotation.Configuration;
        @Configuration public class Config%s {
          @Bean public %s %s() { return null; }
        }
        """
        .formatted(methodName, returnType, methodName);
  }

  @Test
  @DisplayName("One implementing component plus a @Bean of the interface is AMBIGUOUS")
  void componentPlusBeanIsAmbiguous() {
    ProjectModel model =
        analyze(
            service("com.example", "Caller", "Handler handler"),
            "package com.example; public interface Handler {}",
            handlerImpl("RealHandler"),
            beanConfig("Handler", "extraHandler"));

    DependencyModel dependency = dependency(model, "com.example.Caller", "handler");

    assertThat(dependency.resolution()).isEqualTo(DependencyResolution.AMBIGUOUS);
    assertThat(dependency.resolvedType()).isNull();
  }

  @Test
  @DisplayName(
      "An interface provided only by a @Bean of a project subclass resolves to the subclass")
  void beanOfProjectSubclassIsSingleImplementation() {
    ProjectModel model =
        analyze(
            service("com.example", "Caller", "Handler handler"),
            "package com.example; public interface Handler {}",
            "package com.example; public class PlainHandler implements Handler {}",
            beanConfig("PlainHandler", "plainHandler"));

    DependencyModel dependency = dependency(model, "com.example.Caller", "handler");

    assertThat(dependency.resolution()).isEqualTo(DependencyResolution.SINGLE_IMPLEMENTATION);
    assertThat(dependency.resolvedType()).isEqualTo("com.example.PlainHandler");
  }

  @Test
  @DisplayName("An interface provided only by a @Bean of the interface stays on the interface")
  void beanOfInterfaceStaysOnInterface() {
    ProjectModel model =
        analyze(
            service("com.example", "Caller", "Handler handler"),
            "package com.example; public interface Handler {}",
            beanConfig("Handler", "onlyHandler"));

    DependencyModel dependency = dependency(model, "com.example.Caller", "handler");

    assertThat(dependency.resolution()).isEqualTo(DependencyResolution.PROJECT_TYPE);
    assertThat(dependency.resolvedType()).isEqualTo("com.example.Handler");
  }

  @Test
  @DisplayName("A @Bean of a library type does not change an EXTERNAL dependency")
  void beanOfLibraryTypeStaysExternal() {
    ProjectModel model =
        analyze(
            service("com.example", "Caller", "DataSource dataSource")
                .replace(
                    "import org.springframework.stereotype.Service;",
                    "import org.springframework.stereotype.Service;\n"
                        + "import javax.sql.DataSource;"),
            """
            package com.example;
            import javax.sql.DataSource;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            @Configuration public class DataConfig {
              @Bean public DataSource dataSource() { return null; }
            }
            """);

    assertThat(dependency(model, "com.example.Caller", "dataSource").resolution())
        .isEqualTo(DependencyResolution.EXTERNAL);
  }

  @Test
  @DisplayName("List injection includes project types provided by @Bean methods")
  void listIncludesBeanOfProjectType() {
    ProjectModel model =
        analyze(
            service("com.example", "Caller", "List<Handler> handlers")
                .replace("import org.springframework.stereotype.Service;", LIST_IMPORT),
            "package com.example; public interface Handler {}",
            handlerImpl("RealHandler"),
            "package com.example; public class PlainHandler implements Handler {}",
            beanConfig("PlainHandler", "plainHandler"));

    assertThat(dependencies(model, "com.example.Caller", "handlers"))
        .extracting(DependencyModel::resolvedType)
        .containsExactly("com.example.PlainHandler", "com.example.RealHandler");
  }

  @Test
  @DisplayName("Dependencies of a @Configuration class join the graph, so its cycles are detected")
  void configurationClassJoinsCycleDetection() {
    ProjectModel model =
        analyze(
            """
            package com.example;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.context.annotation.Configuration;
            @Configuration public class AppConfig {
              @Autowired Worker worker;
            }
            """,
            service("com.example", "Worker", "AppConfig config"));

    assertThat(dependency(model, "com.example.AppConfig", "worker").resolvedType())
        .isEqualTo("com.example.Worker");
    assertThat(cycles(model)).hasSize(1);
  }

  private static String libraryCaller(String fieldType) {
    return """
        package com.example;
        import com.library.Gateway;
        import java.util.List;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Service;
        @Service public class Caller {
          @Autowired %s gateway;
        }
        """
        .formatted(fieldType);
  }

  private static String libraryImplementation(String name) {
    return """
        package com.example;
        import com.library.Gateway;
        import org.springframework.stereotype.Component;
        @Component public class %s implements Gateway {}
        """
        .formatted(name);
  }

  @Test
  @DisplayName("A library interface with one project implementation resolves to that component")
  void libraryInterfaceWithOneImplementation() {
    ProjectModel model = analyze(libraryCaller("Gateway"), libraryImplementation("HttpGateway"));

    DependencyModel dependency = dependency(model, "com.example.Caller", "gateway");

    assertThat(dependency.resolution()).isEqualTo(DependencyResolution.SINGLE_IMPLEMENTATION);
    assertThat(dependency.resolvedType()).isEqualTo("com.example.HttpGateway");
    assertThat(dependency.declaredType().qualifiedName()).isEqualTo("com.library.Gateway");
  }

  @Test
  @DisplayName("A library interface with several project implementations is AMBIGUOUS")
  void libraryInterfaceWithSeveralImplementations() {
    ProjectModel model =
        analyze(
            libraryCaller("Gateway"),
            libraryImplementation("HttpGateway"),
            libraryImplementation("MockGateway"));

    DependencyModel dependency = dependency(model, "com.example.Caller", "gateway");

    assertThat(dependency.resolution()).isEqualTo(DependencyResolution.AMBIGUOUS);
    assertThat(dependency.resolvedType()).isNull();
  }

  @Test
  @DisplayName("A library class extended by a project component resolves to that component")
  void libraryClassWithProjectSubclass() {
    ProjectModel model =
        analyze(
            """
            package com.example;
            import com.library.Template;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;
            @Service public class Caller {
              @Autowired Template template;
            }
            """,
            """
            package com.example;
            import com.library.Template;
            import org.springframework.stereotype.Component;
            @Component public class CustomTemplate extends Template {}
            """);

    assertThat(dependency(model, "com.example.Caller", "template").resolvedType())
        .isEqualTo("com.example.CustomTemplate");
  }

  @Test
  @DisplayName("A project implementation plus a @Bean of the library interface is AMBIGUOUS")
  void libraryInterfaceWithImplementationAndBean() {
    ProjectModel model =
        analyze(
            libraryCaller("Gateway"),
            libraryImplementation("HttpGateway"),
            """
            package com.example;
            import com.library.Gateway;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            @Configuration public class GatewayConfig {
              @Bean public Gateway extraGateway() { return null; }
            }
            """);

    assertThat(dependency(model, "com.example.Caller", "gateway").resolution())
        .isEqualTo(DependencyResolution.AMBIGUOUS);
  }

  @Test
  @DisplayName("Several @Bean methods of a library type without implementation stay EXTERNAL")
  void severalBeansOfLibraryTypeStayExternal() {
    ProjectModel model =
        analyze(
            libraryCaller("Gateway"),
            """
            package com.example;
            import com.library.Gateway;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            @Configuration public class GatewayConfig {
              @Bean public Gateway first() { return null; }
              @Bean public Gateway second() { return null; }
            }
            """);

    DependencyModel dependency = dependency(model, "com.example.Caller", "gateway");

    assertThat(dependency.resolution()).isEqualTo(DependencyResolution.EXTERNAL);
    assertThat(dependency.resolvedType()).isNull();
  }

  @Test
  @DisplayName("List of a library interface has one entry per project implementation")
  void listOfLibraryInterface() {
    ProjectModel model =
        analyze(
            libraryCaller("List<Gateway>"),
            libraryImplementation("HttpGateway"),
            libraryImplementation("MockGateway"));

    assertThat(dependencies(model, "com.example.Caller", "gateway"))
        .extracting(DependencyModel::resolvedType, DependencyModel::resolution)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "com.example.HttpGateway", DependencyResolution.COLLECTION_ELEMENT),
            org.assertj.core.groups.Tuple.tuple(
                "com.example.MockGateway", DependencyResolution.COLLECTION_ELEMENT));
  }

  @Test
  @DisplayName("A cycle closed through a library interface is detected")
  void reportsCycleThroughLibraryInterface() {
    ProjectModel model =
        analyze(
            libraryCaller("Gateway"),
            """
            package com.example;
            import com.library.Gateway;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Component;
            @Component public class HttpGateway implements Gateway {
              @Autowired Caller caller;
            }
            """);

    assertThat(cycles(model)).hasSize(1);
    assertThat(cycles(model).get(0).message())
        .contains("com.example.Caller", "com.example.HttpGateway");
  }

  @Test
  @DisplayName("A project without sources aggregates to empty packages and dependencies")
  void emptyProjectAggregatesToEmpty() {
    ProjectModel model = analyze();

    assertThat(model.packages()).isEmpty();
    assertThat(model.dependencies()).isEmpty();
    assertThat(model.issues()).isEmpty();
  }

  private static final String GENERIC_HANDLER =
      "package com.example; public interface Handler<T> {}";
  private static final String FOO = "package com.example; public class Foo {}";
  private static final String BAR = "package com.example; public class Bar {}";

  private static String genericHandlerImpl(String name, String implementsClause) {
    return """
        package com.example;
        import org.springframework.stereotype.Component;
        @Component public class %s %s {}
        """
        .formatted(name, implementsClause);
  }

  @Test
  @DisplayName("A collection of a generic type keeps only the beans with matching type arguments")
  void collectionOfGenericTypeMatchesTypeArguments() {
    ProjectModel model =
        analyze(
            service("com.example", "Dispatcher", "List<Handler<Foo>> handlers")
                .replace("import org.springframework.stereotype.Service;", LIST_IMPORT),
            GENERIC_HANDLER,
            FOO,
            BAR,
            genericHandlerImpl("FooHandler", "implements Handler<Foo>"),
            genericHandlerImpl("BarHandler", "implements Handler<Bar>"));

    assertThat(dependencies(model, "com.example.Dispatcher", "handlers"))
        .extracting(DependencyModel::resolvedType, DependencyModel::resolution)
        .containsExactly(tuple("com.example.FooHandler", DependencyResolution.COLLECTION_ELEMENT));
  }

  @Test
  @DisplayName(
      "A bean whose type arguments cannot be checked is recorded as AMBIGUOUS, not guessed")
  void collectionOfGenericTypeWithUncheckableBeanIsAmbiguous() {
    ProjectModel model =
        analyze(
            service("com.example", "Dispatcher", "List<Handler<Foo>> handlers")
                .replace("import org.springframework.stereotype.Service;", LIST_IMPORT),
            GENERIC_HANDLER,
            FOO,
            BAR,
            genericHandlerImpl("FooHandler", "implements Handler<Foo>"),
            "package com.example; public abstract class BaseHandler<T> implements Handler<T> {}",
            genericHandlerImpl("BarHandler", "extends BaseHandler<Bar>"));

    assertThat(dependencies(model, "com.example.Dispatcher", "handlers"))
        .extracting(DependencyModel::resolvedType, DependencyModel::resolution)
        .containsExactly(
            tuple("com.example.FooHandler", DependencyResolution.COLLECTION_ELEMENT),
            tuple(null, DependencyResolution.AMBIGUOUS));
  }

  @Test
  @DisplayName("A single generic injection does not resolve to a bean with other type arguments")
  void singleGenericInjectionSkipsMismatchedImplementation() {
    ProjectModel model =
        analyze(
            service("com.example", "Dispatcher", "Handler<Foo> handler"),
            GENERIC_HANDLER,
            FOO,
            BAR,
            genericHandlerImpl("FooHandler", "implements Handler<Foo>"),
            genericHandlerImpl("BarHandler", "implements Handler<Bar>"));

    DependencyModel dependency = dependency(model, "com.example.Dispatcher", "handler");

    assertThat(dependency.resolution()).isEqualTo(DependencyResolution.SINGLE_IMPLEMENTATION);
    assertThat(dependency.resolvedType()).isEqualTo("com.example.FooHandler");
  }

  @Test
  @DisplayName("A wildcard type argument matches every bean of the generic type")
  void wildcardTypeArgumentMatchesAllBeans() {
    ProjectModel model =
        analyze(
            service("com.example", "Dispatcher", "List<Handler<?>> handlers")
                .replace("import org.springframework.stereotype.Service;", LIST_IMPORT),
            GENERIC_HANDLER,
            FOO,
            BAR,
            genericHandlerImpl("FooHandler", "implements Handler<Foo>"),
            genericHandlerImpl("BarHandler", "implements Handler<Bar>"));

    assertThat(dependencies(model, "com.example.Dispatcher", "handlers"))
        .extracting(DependencyModel::resolvedType)
        .containsExactly("com.example.BarHandler", "com.example.FooHandler");
  }
}
