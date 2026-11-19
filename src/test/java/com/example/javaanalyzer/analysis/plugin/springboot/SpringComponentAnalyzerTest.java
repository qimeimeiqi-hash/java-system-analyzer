package com.example.javaanalyzer.analysis.plugin.springboot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.example.javaanalyzer.analysis.AnalysisContext;
import com.example.javaanalyzer.analysis.AnalysisTestSupport;
import com.example.javaanalyzer.ir.ComponentModel;
import com.example.javaanalyzer.ir.ComponentRole;
import com.example.javaanalyzer.ir.InjectionKind;
import com.example.javaanalyzer.ir.InjectionModel;
import com.example.javaanalyzer.ir.MemberOrigin;
import com.example.javaanalyzer.ir.MethodModel;
import com.example.javaanalyzer.ir.ParameterModel;
import com.example.javaanalyzer.ir.TypeModel;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpringComponentAnalyzerTest {

  @TempDir Path projectRoot;

  private List<ComponentModel> analyze(String... sources) {
    AnalysisContext context = AnalysisTestSupport.contextOf(projectRoot, sources);
    new SpringComponentAnalyzer().analyze(context);
    return context.toProjectModel().components();
  }

  private static ComponentModel component(List<ComponentModel> components, String typeName) {
    return components.stream()
        .filter(c -> c.typeName().equals(typeName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No component " + typeName + " in " + components));
  }

  private InjectionModel injectionOfFieldType(String fieldType, String... extraImports) {
    StringBuilder imports = new StringBuilder();
    for (String extraImport : extraImports) {
      imports.append("import ").append(extraImport).append(";\n");
    }
    List<ComponentModel> components =
        analyze(
            "package com.example;\n"
                + "import org.springframework.beans.factory.annotation.Autowired;\n"
                + "import org.springframework.stereotype.Service;\n"
                + imports
                + "@Service public class Caller {\n"
                + "  @Autowired "
                + fieldType
                + " target;\n"
                + "}\n",
            "package com.example; public interface Handler {}");
    return component(components, "com.example.Caller").injections().get(0);
  }

  @Test
  @DisplayName("A plain type is its own bean type and injects a single bean")
  void plainTypeIsNotUnwrapped() {
    InjectionModel injection = injectionOfFieldType("Handler");

    assertThat(injection.beanType().qualifiedName()).isEqualTo("com.example.Handler");
    assertThat(injection.multipleBeans()).isFalse();
  }

  @Test
  @DisplayName("Optional and provider types are unwrapped and still inject a single bean")
  void singleBeanWrappersAreUnwrapped() {
    InjectionModel optional = injectionOfFieldType("Optional<Handler>", "java.util.Optional");

    assertThat(optional.beanType().qualifiedName()).isEqualTo("com.example.Handler");
    assertThat(optional.type().text()).isEqualTo("Optional<Handler>");
    assertThat(optional.multipleBeans()).isFalse();
  }

  @Test
  @DisplayName("ObjectProvider is unwrapped")
  void objectProviderIsUnwrapped() {
    InjectionModel provider =
        injectionOfFieldType(
            "ObjectProvider<Handler>", "org.springframework.beans.factory.ObjectProvider");

    assertThat(provider.beanType().qualifiedName()).isEqualTo("com.example.Handler");
    assertThat(provider.multipleBeans()).isFalse();
  }

  @Test
  @DisplayName("jakarta.inject.Provider is unwrapped")
  void jakartaProviderIsUnwrapped() {
    InjectionModel provider = injectionOfFieldType("Provider<Handler>", "jakarta.inject.Provider");

    assertThat(provider.beanType().qualifiedName()).isEqualTo("com.example.Handler");
  }

  @Test
  @DisplayName("List injects every matching bean of its element type")
  void listInjectsMultipleBeans() {
    InjectionModel injection = injectionOfFieldType("List<Handler>", "java.util.List");

    assertThat(injection.beanType().qualifiedName()).isEqualTo("com.example.Handler");
    assertThat(injection.multipleBeans()).isTrue();
  }

  @Test
  @DisplayName("Map<String, T> injects every matching bean of its value type")
  void stringKeyedMapInjectsMultipleBeans() {
    InjectionModel injection = injectionOfFieldType("Map<String, Handler>", "java.util.Map");

    assertThat(injection.beanType().qualifiedName()).isEqualTo("com.example.Handler");
    assertThat(injection.multipleBeans()).isTrue();
  }

  @Test
  @DisplayName("A Map without String keys is not bean collection injection and stays as declared")
  void nonStringKeyedMapIsNotUnwrapped() {
    InjectionModel injection = injectionOfFieldType("Map<Long, Handler>", "java.util.Map");

    assertThat(injection.beanType().qualifiedName()).isEqualTo("java.util.Map");
    assertThat(injection.multipleBeans()).isFalse();
  }

  @Test
  @DisplayName("An array injects every matching bean of its component type")
  void arrayInjectsMultipleBeans() {
    InjectionModel injection = injectionOfFieldType("Handler[]");

    assertThat(injection.beanType().qualifiedName()).isEqualTo("com.example.Handler");
    assertThat(injection.multipleBeans()).isTrue();
  }

  @Test
  @DisplayName("A wildcard element type uses its bound")
  void wildcardElementUsesBound() {
    InjectionModel injection = injectionOfFieldType("List<? extends Handler>", "java.util.List");

    assertThat(injection.beanType().qualifiedName()).isEqualTo("com.example.Handler");
    assertThat(injection.multipleBeans()).isTrue();
  }

  @Test
  @DisplayName("Nested wrappers are unwrapped and keep the multiple beans meaning")
  void nestedWrappersAreUnwrapped() {
    InjectionModel injection =
        injectionOfFieldType(
            "ObjectProvider<List<Handler>>",
            "java.util.List",
            "org.springframework.beans.factory.ObjectProvider");

    assertThat(injection.beanType().qualifiedName()).isEqualTo("com.example.Handler");
    assertThat(injection.multipleBeans()).isTrue();
  }

  @Test
  @DisplayName("A raw List has no element type and stays as declared")
  void rawListStaysAsDeclared() {
    InjectionModel injection = injectionOfFieldType("List", "java.util.List");

    assertThat(injection.beanType().qualifiedName()).isEqualTo("java.util.List");
    assertThat(injection.multipleBeans()).isFalse();
  }

  @Test
  @DisplayName("An unbounded wildcard cannot be determined and stays unresolved")
  void unboundedWildcardIsUnresolved() {
    InjectionModel injection = injectionOfFieldType("List<?>", "java.util.List");

    assertThat(injection.beanType().qualifiedName()).isNull();
  }

  @Test
  @DisplayName("List imported on demand injects every matching bean")
  void onDemandImportedListIsUnwrapped() {
    InjectionModel injection = injectionOfFieldType("List<Handler>", "java.util.*");

    assertThat(injection.beanType().qualifiedName()).isEqualTo("com.example.Handler");
    assertThat(injection.multipleBeans()).isTrue();
  }

  @Test
  @DisplayName("Optional imported on demand injects a single bean")
  void onDemandImportedOptionalIsUnwrapped() {
    InjectionModel injection = injectionOfFieldType("Optional<Handler>", "java.util.*");

    assertThat(injection.beanType().qualifiedName()).isEqualTo("com.example.Handler");
    assertThat(injection.multipleBeans()).isFalse();
  }

  @Test
  @DisplayName("Map<String, T> imported on demand injects every matching bean")
  void onDemandImportedMapIsUnwrapped() {
    InjectionModel injection = injectionOfFieldType("Map<String, Handler>", "java.util.*");

    assertThat(injection.beanType().qualifiedName()).isEqualTo("com.example.Handler");
    assertThat(injection.multipleBeans()).isTrue();
  }

  @Test
  @DisplayName("A List from an unrelated on-demand import stays unresolved")
  void listFromUnrelatedImportIsNotUnwrapped() {
    InjectionModel injection = injectionOfFieldType("List<Handler>", "com.other.*");

    assertThat(injection.beanType().text()).isEqualTo("List<Handler>");
    assertThat(injection.multipleBeans()).isFalse();
  }

  @Test
  @DisplayName("A project class named List shadows the on-demand import and is kept")
  void projectListShadowsOnDemandImport() {
    List<ComponentModel> components =
        analyze(
            """
            package com.example;
            import java.util.*;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;
            @Service public class Caller {
              @Autowired List<Handler> target;
            }
            """,
            "package com.example; public interface Handler {}",
            "package com.example; public class List<T> {}");

    InjectionModel injection = component(components, "com.example.Caller").injections().get(0);

    assertThat(injection.beanType().qualifiedName()).isEqualTo("com.example.List");
    assertThat(injection.multipleBeans()).isFalse();
  }

  @Test
  @DisplayName("A @Configuration class is a CONFIGURATION component with its field injection")
  void configurationClassRecordsFieldInjection() {
    List<ComponentModel> components =
        analyze(
            """
            package com.example.config;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.context.annotation.Configuration;
            @Configuration public class SecurityConfig {
              @Autowired private UserLookup userLookup;
            }
            """,
            "package com.example.config; public interface UserLookup {}");

    ComponentModel config = component(components, "com.example.config.SecurityConfig");

    assertThat(config.role()).isEqualTo(ComponentRole.CONFIGURATION);
    assertThat(config.injections())
        .extracting(InjectionModel::kind, InjectionModel::memberName)
        .containsExactly(tuple(InjectionKind.FIELD, "userLookup"));
  }

  @Test
  @DisplayName("A @Configuration class records constructor injection")
  void configurationClassRecordsConstructorInjection() {
    List<ComponentModel> components =
        analyze(
            """
            package com.example.config;
            import org.springframework.context.annotation.Configuration;
            @Configuration public class SecurityConfig {
              private final UserLookup userLookup;
              public SecurityConfig(UserLookup userLookup) { this.userLookup = userLookup; }
            }
            """,
            "package com.example.config; public interface UserLookup {}");

    assertThat(component(components, "com.example.config.SecurityConfig").injections())
        .extracting(InjectionModel::kind, InjectionModel::memberName)
        .containsExactly(tuple(InjectionKind.CONSTRUCTOR, "userLookup"));
  }

  @Test
  @DisplayName("A @SpringBootApplication class is not a component")
  void springBootApplicationIsNotAComponent() {
    List<ComponentModel> components =
        analyze(
            """
            package com.example;
            import org.springframework.boot.autoconfigure.SpringBootApplication;
            @SpringBootApplication public class App {}
            """);

    assertThat(components).isEmpty();
  }

  @Test
  @DisplayName("Stereotype annotations map to roles; plain classes are not components")
  void detectsRolesFromStereotypes() {
    List<ComponentModel> components =
        analyze(
            """
            package com.example.web;
            import org.springframework.web.bind.annotation.RestController;
            @RestController class UserApi {}
            """,
            """
            package com.example.web;
            import org.springframework.stereotype.Controller;
            @Controller class PageController {}
            """,
            """
            package com.example.service;
            import org.springframework.stereotype.Service;
            @Service class UserService {}
            """,
            """
            package com.example.repo;
            import org.springframework.stereotype.Repository;
            @Repository class JdbcUserDao {}
            """,
            """
            package com.example.support;
            import org.springframework.stereotype.*;
            @Component class Clock {}
            """,
            """
            package com.example.dto;
            class UserDto {}
            """);

    assertThat(components)
        .extracting(ComponentModel::typeName, ComponentModel::role)
        .containsExactlyInAnyOrder(
            tuple("com.example.web.UserApi", ComponentRole.CONTROLLER),
            tuple("com.example.web.PageController", ComponentRole.CONTROLLER),
            tuple("com.example.service.UserService", ComponentRole.SERVICE),
            tuple("com.example.repo.JdbcUserDao", ComponentRole.REPOSITORY),
            tuple("com.example.support.Clock", ComponentRole.COMPONENT));
  }

  @Test
  @DisplayName("A project-local annotation named Service does not make a component")
  void ignoresProjectLocalAnnotationWithSameName() {
    List<ComponentModel> components =
        analyze(
            """
            package com.example.marker;
            public @interface Service {}
            """,
            """
            package com.example.app;
            import com.example.marker.Service;
            @Service class NotSpring {}
            """);

    assertThat(components).isEmpty();
  }

  @Test
  @DisplayName("Field injection via @Autowired, jakarta @Inject and @Resource, with @Qualifier")
  void detectsFieldInjection() {
    ComponentModel service =
        component(
            analyze(
                """
                package com.example.service;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.beans.factory.annotation.Qualifier;
                import org.springframework.stereotype.Service;
                @Service
                class OrderService {
                  @Autowired private OrderRepository orderRepository;
                  @jakarta.inject.Inject private MailSender mailSender;
                  @javax.annotation.Resource private AuditLog auditLog;
                  @Autowired @Qualifier("fast") private Clock clock;
                  private String notInjected;
                  @Autowired private static Object staticIgnored;
                }
                """),
            "com.example.service.OrderService");

    assertThat(service.injections())
        .extracting(
            InjectionModel::kind,
            InjectionModel::memberName,
            i -> i.type().text(),
            InjectionModel::origin,
            InjectionModel::qualifier)
        .containsExactly(
            tuple(
                InjectionKind.FIELD,
                "orderRepository",
                "OrderRepository",
                MemberOrigin.SOURCE,
                null),
            tuple(InjectionKind.FIELD, "mailSender", "MailSender", MemberOrigin.SOURCE, null),
            tuple(InjectionKind.FIELD, "auditLog", "AuditLog", MemberOrigin.SOURCE, null),
            tuple(InjectionKind.FIELD, "clock", "Clock", MemberOrigin.SOURCE, "fast"));
  }

  @Test
  @DisplayName("A single constructor injects its parameters without @Autowired")
  void detectsImplicitSingleConstructorInjection() {
    ComponentModel service =
        component(
            analyze(
                """
                package com.example.service;
                import org.springframework.stereotype.Service;
                import org.springframework.beans.factory.annotation.Qualifier;
                @Service
                class UserService {
                  private final UserRepository userRepository;
                  private final Clock clock;
                  UserService(UserRepository userRepository, @Qualifier("utc") Clock clock) {
                    this.userRepository = userRepository;
                    this.clock = clock;
                  }
                }
                """),
            "com.example.service.UserService");

    assertThat(service.injections())
        .extracting(InjectionModel::kind, InjectionModel::memberName, InjectionModel::qualifier)
        .containsExactly(
            tuple(InjectionKind.CONSTRUCTOR, "userRepository", null),
            tuple(InjectionKind.CONSTRUCTOR, "clock", "utc"));
  }

  @Test
  @DisplayName("With several constructors only the @Autowired one injects")
  void usesAutowiredConstructorWhenSeveralExist() {
    ComponentModel service =
        component(
            analyze(
                """
                package com.example.service;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.stereotype.Service;
                @Service
                class ReportService {
                  ReportService() {}
                  @Autowired ReportService(ReportRepository reportRepository) {}
                }
                """),
            "com.example.service.ReportService");

    assertThat(service.injections())
        .extracting(InjectionModel::memberName)
        .containsExactly("reportRepository");
  }

  @Test
  @DisplayName("With several constructors and none @Autowired nothing is injected by constructor")
  void noConstructorInjectionWhenAmbiguous() {
    ComponentModel service =
        component(
            analyze(
                """
                package com.example.service;
                import org.springframework.stereotype.Service;
                @Service
                class AmbiguousService {
                  AmbiguousService() {}
                  AmbiguousService(Object a) {}
                }
                """),
            "com.example.service.AmbiguousService");

    assertThat(service.injections()).isEmpty();
  }

  @Test
  @DisplayName(
      "A Lombok-generated constructor (e.g. @RequiredArgsConstructor) is constructor injection")
  void detectsInjectionThroughLombokConstructor() {
    AnalysisContext context =
        AnalysisTestSupport.contextOf(
            projectRoot,
            """
            package com.example.service;
            import lombok.RequiredArgsConstructor;
            import org.springframework.stereotype.Service;
            @Service
            @RequiredArgsConstructor
            class PaymentService {
              private final PaymentRepository paymentRepository;
            }
            """);
    TypeModel type = context.type("com.example.service.PaymentService");
    TypeModel withLombokConstructor =
        type.withAdditionalMembers(
            List.of(
                new MethodModel(
                    "PaymentService",
                    null,
                    List.of(
                        new ParameterModel(
                            "paymentRepository", type.fields().get(0).type(), List.of())),
                    List.of("public"),
                    List.of(),
                    null,
                    null,
                    List.of(),
                    MemberOrigin.LOMBOK,
                    "RequiredArgsConstructor")),
            List.of());
    context.replaceType(withLombokConstructor);

    new SpringComponentAnalyzer().analyze(context);

    ComponentModel service =
        component(context.toProjectModel().components(), "com.example.service.PaymentService");
    assertThat(service.injections())
        .extracting(InjectionModel::kind, InjectionModel::memberName, InjectionModel::origin)
        .containsExactly(
            tuple(InjectionKind.CONSTRUCTOR, "paymentRepository", MemberOrigin.LOMBOK));
  }

  @Test
  @DisplayName("@Autowired setter methods are setter injection")
  void detectsSetterInjection() {
    ComponentModel component =
        component(
            analyze(
                """
                package com.example.support;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.stereotype.Component;
                @Component
                class Notifier {
                  private MailSender mailSender;
                  @Autowired void setMailSender(MailSender mailSender) { this.mailSender = mailSender; }
                  void setOther(Object other) {}
                }
                """),
            "com.example.support.Notifier");

    assertThat(component.injections())
        .extracting(InjectionModel::kind, InjectionModel::memberName)
        .containsExactly(tuple(InjectionKind.SETTER, "mailSender"));
  }

  @Test
  @DisplayName("@Inject and @Resource setters (javax and jakarta) are setter injection")
  void detectsJsr330AndResourceSetterInjection() {
    ComponentModel component =
        component(
            analyze(
                """
                package com.example.support;
                import jakarta.annotation.Resource;
                import javax.inject.Inject;
                import org.springframework.stereotype.Component;
                @Component
                class Notifier {
                  @Inject void setMailSender(MailSender mailSender) {}
                  @Resource void setClock(Clock clock) {}
                  void setOther(Object other) {}
                }
                """),
            "com.example.support.Notifier");

    assertThat(component.injections())
        .extracting(InjectionModel::kind, InjectionModel::memberName)
        .containsExactly(
            tuple(InjectionKind.SETTER, "mailSender"), tuple(InjectionKind.SETTER, "clock"));
  }

  @Test
  @DisplayName("With several constructors, the @Inject one is the injecting constructor")
  void injectAnnotatedConstructorIsChosenAmongSeveral() {
    ComponentModel component =
        component(
            analyze(
                """
                package com.example.order;
                import jakarta.inject.Inject;
                import org.springframework.stereotype.Service;
                @Service
                class OrderService {
                  OrderService() {}
                  @Inject OrderService(OrderRepository orderRepository) {}
                }
                """),
            "com.example.order.OrderService");

    assertThat(component.injections())
        .extracting(InjectionModel::kind, InjectionModel::memberName)
        .containsExactly(tuple(InjectionKind.CONSTRUCTOR, "orderRepository"));
  }

  @Test
  @DisplayName("With several constructors and none annotated, no constructor injection is recorded")
  void severalUnannotatedConstructorsInjectNothing() {
    ComponentModel component =
        component(
            analyze(
                """
                package com.example.order;
                import org.springframework.stereotype.Service;
                @Service
                class OrderService {
                  OrderService() {}
                  OrderService(OrderRepository orderRepository) {}
                }
                """),
            "com.example.order.OrderService");

    assertThat(component.injections()).isEmpty();
  }

  @Test
  @DisplayName("A record component's implicit canonical constructor is constructor injection")
  void recordComponentsAreConstructorInjection() {
    ComponentModel component =
        component(
            analyze(
                """
                package com.example.order;
                import org.springframework.beans.factory.annotation.Qualifier;
                import org.springframework.stereotype.Service;
                @Service
                public record OrderService(
                    OrderRepository orderRepository, @Qualifier("fast") Clock clock) {
                  public OrderService {
                    java.util.Objects.requireNonNull(orderRepository);
                  }
                }
                """,
                "package com.example.order; public interface OrderRepository {}"),
            "com.example.order.OrderService");

    assertThat(component.injections())
        .extracting(
            InjectionModel::kind,
            InjectionModel::memberName,
            InjectionModel::origin,
            InjectionModel::qualifier)
        .containsExactly(
            tuple(InjectionKind.CONSTRUCTOR, "orderRepository", MemberOrigin.SOURCE, null),
            tuple(InjectionKind.CONSTRUCTOR, "clock", MemberOrigin.SOURCE, "fast"));
    assertThat(component.injections().get(0).type().qualifiedName())
        .isEqualTo("com.example.order.OrderRepository");
  }

  @Test
  @DisplayName("A record with an explicit canonical constructor uses that constructor")
  void recordWithExplicitCanonicalConstructorUsesIt() {
    ComponentModel component =
        component(
            analyze(
                """
                package com.example.order;
                import org.springframework.stereotype.Service;
                @Service
                public record OrderService(OrderRepository orderRepository) {
                  public OrderService(OrderRepository orderRepository) {
                    this.orderRepository = orderRepository;
                  }
                }
                """),
            "com.example.order.OrderService");

    assertThat(component.injections())
        .extracting(InjectionModel::kind, InjectionModel::memberName)
        .containsExactly(tuple(InjectionKind.CONSTRUCTOR, "orderRepository"));
  }
}
