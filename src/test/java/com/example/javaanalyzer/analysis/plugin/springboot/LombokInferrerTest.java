package com.example.javaanalyzer.analysis.plugin.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.analysis.AnalysisContext;
import com.example.javaanalyzer.analysis.AnalysisTestSupport;
import com.example.javaanalyzer.ir.MemberOrigin;
import com.example.javaanalyzer.ir.MethodModel;
import com.example.javaanalyzer.ir.ParameterModel;
import com.example.javaanalyzer.ir.TypeModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LombokInferrerTest {

  @TempDir Path tempDir;

  private TypeModel infer(String qualifiedName, String... sources) {
    AnalysisContext context = AnalysisTestSupport.contextOf(tempDir, sources);
    new LombokInferrer().apply(context);
    return context.type(qualifiedName);
  }

  private static List<MethodModel> lombokMethods(TypeModel type) {
    return type.methods().stream().filter(m -> m.origin() == MemberOrigin.LOMBOK).toList();
  }

  private static List<MethodModel> lombokConstructors(TypeModel type) {
    return type.constructors().stream().filter(m -> m.origin() == MemberOrigin.LOMBOK).toList();
  }

  private static List<String> names(List<MethodModel> methods) {
    return methods.stream().map(MethodModel::name).toList();
  }

  private static Optional<MethodModel> method(TypeModel type, String name) {
    return type.methods().stream().filter(m -> m.name().equals(name)).findFirst();
  }

  private static List<String> parameterNames(MethodModel method) {
    return method.parameters().stream().map(ParameterModel::name).toList();
  }

  private static List<String> parameterTypes(MethodModel method) {
    return method.parameters().stream().map(p -> p.type().text()).toList();
  }

  @Test
  @DisplayName(
      "@Data generates getters, setters for non-final fields, required-args constructor,"
          + " toString, equals and hashCode")
  void dataGeneratesAllMembers() {
    TypeModel type =
        infer(
            "com.example.User",
            """
            package com.example;
            import lombok.Data;
            @Data
            public class User {
              private final Long id;
              private String name;
            }
            """);

    assertThat(names(lombokMethods(type)))
        .containsExactly("getId", "getName", "setName", "toString", "equals", "hashCode");
    assertThat(lombokMethods(type)).allMatch(m -> "Data".equals(m.generatedBy()));
    assertThat(lombokMethods(type)).allMatch(m -> m.annotations().isEmpty() && m.javadoc() == null);

    MethodModel getId = method(type, "getId").orElseThrow();
    assertThat(getId.returnType().text()).isEqualTo("Long");
    assertThat(getId.returnType().qualifiedName()).isEqualTo("java.lang.Long");
    assertThat(getId.parameters()).isEmpty();
    assertThat(getId.modifiers()).containsExactly("public");

    MethodModel setName = method(type, "setName").orElseThrow();
    assertThat(setName.returnType().text()).isEqualTo("void");
    assertThat(setName.returnType().qualifiedName()).isEqualTo("void");
    assertThat(parameterNames(setName)).containsExactly("name");
    assertThat(parameterTypes(setName)).containsExactly("String");

    assertThat(method(type, "toString").orElseThrow().returnType().qualifiedName())
        .isEqualTo("java.lang.String");
    MethodModel equals = method(type, "equals").orElseThrow();
    assertThat(equals.returnType().text()).isEqualTo("boolean");
    assertThat(parameterTypes(equals)).containsExactly("Object");
    assertThat(method(type, "hashCode").orElseThrow().returnType().text()).isEqualTo("int");

    assertThat(lombokConstructors(type)).hasSize(1);
    MethodModel constructor = lombokConstructors(type).get(0);
    assertThat(constructor.name()).isEqualTo("User");
    assertThat(constructor.returnType()).isNull();
    assertThat(constructor.generatedBy()).isEqualTo("Data");
    assertThat(constructor.modifiers()).containsExactly("public");
    assertThat(parameterNames(constructor)).containsExactly("id");
    assertThat(parameterTypes(constructor)).containsExactly("Long");
  }

  @Test
  @DisplayName(
      "class-level @Getter covers all non-static fields; field-level @Setter only its field")
  void classLevelGetterAndFieldLevelSetter() {
    TypeModel type =
        infer(
            "com.example.Item",
            """
            package com.example;
            import lombok.Getter;
            import lombok.Setter;
            @Getter
            public class Item {
              private static int counter;
              private String code;
              @Setter private int quantity;
            }
            """);

    assertThat(names(lombokMethods(type))).containsExactly("getCode", "getQuantity", "setQuantity");
    assertThat(method(type, "getCode").orElseThrow().generatedBy()).isEqualTo("Getter");
    assertThat(method(type, "setQuantity").orElseThrow().generatedBy()).isEqualTo("Setter");
    assertThat(parameterTypes(method(type, "setQuantity").orElseThrow())).containsExactly("int");
    assertThat(lombokConstructors(type)).isEmpty();
  }

  @Test
  @DisplayName("field-level AccessLevel.NONE suppresses and PROTECTED changes the getter modifier")
  void accessLevelNoneAndProtected() {
    TypeModel type =
        infer(
            "com.example.Account",
            """
            package com.example;
            import lombok.AccessLevel;
            import lombok.Getter;
            import lombok.Setter;
            @Getter
            @Setter(value = AccessLevel.PACKAGE)
            public class Account {
              @Getter(AccessLevel.NONE) private String password;
              @Getter(AccessLevel.PROTECTED) private String owner;
              @Setter(AccessLevel.PRIVATE) private String note;
            }
            """);

    assertThat(names(lombokMethods(type)))
        .containsExactly("setPassword", "getOwner", "setOwner", "getNote", "setNote");
    assertThat(method(type, "getOwner").orElseThrow().modifiers()).containsExactly("protected");
    assertThat(method(type, "setOwner").orElseThrow().modifiers()).isEmpty();
    assertThat(method(type, "setNote").orElseThrow().modifiers()).containsExactly("private");
    assertThat(method(type, "getNote").orElseThrow().modifiers()).containsExactly("public");
  }

  @Test
  @DisplayName(
      "primitive boolean uses isX (no double prefix for isActive); Boolean wrapper uses getX")
  void booleanAccessorNaming() {
    TypeModel type =
        infer(
            "com.example.Flags",
            """
            package com.example;
            import lombok.Getter;
            import lombok.Setter;
            @Getter
            @Setter
            public class Flags {
              private boolean enabled;
              private boolean isActive;
              private Boolean deleted;
              private boolean island;
            }
            """);

    assertThat(names(lombokMethods(type)))
        .containsExactly(
            "isEnabled",
            "setEnabled",
            "isActive",
            "setActive",
            "getDeleted",
            "setDeleted",
            "isIsland",
            "setIsland");
    assertThat(parameterNames(method(type, "setActive").orElseThrow())).containsExactly("isActive");
  }

  @Test
  @DisplayName("final fields get getters but no setters")
  void finalFieldHasNoSetter() {
    TypeModel type =
        infer(
            "com.example.Config",
            """
            package com.example;
            import lombok.Getter;
            import lombok.Setter;
            @Getter
            @Setter
            public class Config {
              private final String key = "k";
              private String value;
            }
            """);

    assertThat(names(lombokMethods(type))).containsExactly("getKey", "getValue", "setValue");
  }

  @Test
  @DisplayName("@RequiredArgsConstructor takes uninitialized final and @NonNull fields in order")
  void requiredArgsConstructorForServiceInjection() {
    TypeModel type =
        infer(
            "com.example.service.UserService",
            """
            package com.example.service;
            import com.example.repository.UserRepository;
            import lombok.NonNull;
            import lombok.RequiredArgsConstructor;
            @RequiredArgsConstructor
            public class UserService {
              private static final String PREFIX = "u";
              private final UserRepository userRepository;
              private final int limit = 10;
              @NonNull private String region;
              @NonNull private String fallback = "x";
              private String optional;
              private final MailSender mailSender;
            }
            """,
            """
            package com.example.repository;
            public interface UserRepository {}
            """,
            """
            package com.example.service;
            public interface MailSender {}
            """);

    assertThat(lombokMethods(type)).isEmpty();
    assertThat(lombokConstructors(type)).hasSize(1);
    MethodModel constructor = lombokConstructors(type).get(0);
    assertThat(constructor.name()).isEqualTo("UserService");
    assertThat(constructor.generatedBy()).isEqualTo("RequiredArgsConstructor");
    assertThat(constructor.modifiers()).containsExactly("public");
    assertThat(parameterNames(constructor))
        .containsExactly("userRepository", "region", "mailSender");
    assertThat(constructor.parameters().get(0).type().qualifiedName())
        .isEqualTo("com.example.repository.UserRepository");
    assertThat(constructor.parameters()).allMatch(p -> p.annotations().isEmpty());
  }

  @Test
  @DisplayName("@AllArgsConstructor skips initialized finals; @NoArgsConstructor has no parameters")
  void allArgsAndNoArgsConstructors() {
    TypeModel type =
        infer(
            "com.example.Order",
            """
            package com.example;
            import lombok.AccessLevel;
            import lombok.AllArgsConstructor;
            import lombok.NoArgsConstructor;
            @NoArgsConstructor(access = AccessLevel.PROTECTED)
            @AllArgsConstructor
            public class Order {
              private static long sequence;
              private Long id;
              private final String type = "A";
              private final String status;
            }
            """);

    assertThat(lombokConstructors(type)).hasSize(2);
    MethodModel noArgs = lombokConstructors(type).get(0);
    assertThat(noArgs.generatedBy()).isEqualTo("NoArgsConstructor");
    assertThat(noArgs.parameters()).isEmpty();
    assertThat(noArgs.modifiers()).containsExactly("protected");
    MethodModel allArgs = lombokConstructors(type).get(1);
    assertThat(allArgs.generatedBy()).isEqualTo("AllArgsConstructor");
    assertThat(parameterNames(allArgs)).containsExactly("id", "status");
    assertThat(allArgs.modifiers()).containsExactly("public");
  }

  @Test
  @DisplayName(
      "@Value generates getters, all-args constructor and toString/equals/hashCode, no setters")
  void valueClass() {
    TypeModel type =
        infer(
            "com.example.Money",
            """
            package com.example;
            import lombok.Value;
            @Value
            public class Money {
              String currency;
              long amount;
              String label = "m";
            }
            """);

    assertThat(names(lombokMethods(type)))
        .containsExactly("getCurrency", "getAmount", "getLabel", "toString", "equals", "hashCode");
    assertThat(lombokMethods(type)).allMatch(m -> "Value".equals(m.generatedBy()));
    assertThat(lombokConstructors(type)).hasSize(1);
    MethodModel constructor = lombokConstructors(type).get(0);
    assertThat(constructor.generatedBy()).isEqualTo("Value");
    assertThat(parameterNames(constructor)).containsExactly("currency", "amount");
    assertThat(parameterTypes(constructor)).containsExactly("String", "long");
  }

  @Test
  @DisplayName("@Builder generates static builder() and a package-private all-args constructor")
  void builderClass() {
    TypeModel type =
        infer(
            "com.example.Product",
            """
            package com.example;
            import lombok.Builder;
            @Builder
            public class Product {
              private String name;
              private int price;
            }
            """);

    assertThat(names(lombokMethods(type))).containsExactly("builder");
    MethodModel builder = method(type, "builder").orElseThrow();
    assertThat(builder.modifiers()).containsExactly("public", "static");
    assertThat(builder.generatedBy()).isEqualTo("Builder");
    assertThat(builder.returnType().text()).isEqualTo("ProductBuilder");
    assertThat(builder.returnType().qualifiedName())
        .isEqualTo("com.example.Product.ProductBuilder");
    assertThat(builder.parameters()).isEmpty();

    assertThat(lombokConstructors(type)).hasSize(1);
    MethodModel constructor = lombokConstructors(type).get(0);
    assertThat(constructor.generatedBy()).isEqualTo("Builder");
    assertThat(constructor.modifiers()).isEmpty();
    assertThat(parameterNames(constructor)).containsExactly("name", "price");
  }

  @Test
  @DisplayName("@Builder does not add a constructor when an explicit constructor annotation exists")
  void builderWithExplicitConstructorAnnotation() {
    TypeModel type =
        infer(
            "com.example.Product",
            """
            package com.example;
            import lombok.*;
            @Data
            @Builder
            @NoArgsConstructor
            @AllArgsConstructor
            public class Product {
              private String name;
            }
            """);

    assertThat(lombokConstructors(type))
        .extracting(MethodModel::generatedBy)
        .containsExactly("NoArgsConstructor", "AllArgsConstructor");
  }

  @Test
  @DisplayName("an explicitly written constructor suppresses @Data's required-args constructor")
  void explicitConstructorSuppressesDataConstructor() {
    TypeModel type =
        infer(
            "com.example.Customer",
            """
            package com.example;
            import lombok.Data;
            @Data
            public class Customer {
              private final String code;
              public Customer() { this.code = "c"; }
            }
            """);

    assertThat(lombokConstructors(type)).isEmpty();
    assertThat(type.constructors()).hasSize(1);
    assertThat(names(lombokMethods(type)))
        .containsExactly("getCode", "toString", "equals", "hashCode");
  }

  @Test
  @DisplayName("an explicit constructor annotation suppresses @Data's required-args constructor")
  void explicitConstructorAnnotationSuppressesDataConstructor() {
    TypeModel type =
        infer(
            "com.example.Customer",
            """
            package com.example;
            import lombok.Data;
            import lombok.NoArgsConstructor;
            @Data
            @NoArgsConstructor
            public class Customer {
              private String code;
            }
            """);

    assertThat(lombokConstructors(type))
        .extracting(MethodModel::generatedBy)
        .containsExactly("NoArgsConstructor");
  }

  @Test
  @DisplayName("hand-written getter and toString are not duplicated")
  void existingMethodsAreNotDuplicated() {
    TypeModel type =
        infer(
            "com.example.Person",
            """
            package com.example;
            import lombok.Data;
            @Data
            public class Person {
              private String name;
              public String getName() { return name.trim(); }
              @Override public String toString() { return name; }
            }
            """);

    assertThat(type.methods().stream().filter(m -> m.name().equals("getName"))).hasSize(1);
    assertThat(method(type, "getName").orElseThrow().origin()).isEqualTo(MemberOrigin.SOURCE);
    assertThat(names(lombokMethods(type))).containsExactly("setName", "equals", "hashCode");
  }

  @Test
  @DisplayName("wildcard import lombok.* is recognised")
  void wildcardImport() {
    TypeModel type =
        infer(
            "com.example.Tag",
            """
            package com.example;
            import lombok.*;
            @Getter
            @RequiredArgsConstructor
            public class Tag {
              private final String label;
            }
            """);

    assertThat(names(lombokMethods(type))).containsExactly("getLabel");
    assertThat(parameterNames(lombokConstructors(type).get(0))).containsExactly("label");
  }

  @Test
  @DisplayName("fully qualified @lombok.Data is recognised")
  void fullyQualifiedAnnotation() {
    TypeModel type =
        infer(
            "com.example.Tag",
            """
            package com.example;
            @lombok.Data
            public class Tag {
              private String label;
            }
            """);

    assertThat(names(lombokMethods(type)))
        .containsExactly("getLabel", "setLabel", "toString", "equals", "hashCode");
    assertThat(lombokConstructors(type)).hasSize(1);
    assertThat(lombokConstructors(type).get(0).parameters()).isEmpty();
  }

  @Test
  @DisplayName("a project-local annotation named Data is not treated as Lombok")
  void projectLocalDataAnnotationIgnored() {
    TypeModel type =
        infer(
            "com.example.Report",
            """
            package com.example;
            @Data
            public class Report {
              private final String title;
            }
            """,
            """
            package com.example;
            public @interface Data {}
            """);

    assertThat(lombokMethods(type)).isEmpty();
    assertThat(lombokConstructors(type)).isEmpty();
  }

  @Test
  @DisplayName("interfaces are ignored even when annotated")
  void interfaceIgnored() {
    TypeModel type =
        infer(
            "com.example.Named",
            """
            package com.example;
            import lombok.Getter;
            @Getter
            public interface Named {
              String PREFIX = "n";
            }
            """);

    assertThat(type.methods()).isEmpty();
    assertThat(type.constructors()).isEmpty();
  }

  @Test
  @DisplayName("@Getter on an enum generates getters for its fields")
  void enumGetter() {
    TypeModel type =
        infer(
            "com.example.Status",
            """
            package com.example;
            import lombok.Getter;
            @Getter
            public enum Status {
              ACTIVE("a");
              private final String code;
              Status(String code) { this.code = code; }
            }
            """);

    assertThat(names(lombokMethods(type))).containsExactly("getCode");
  }

  @Test
  @DisplayName("standalone @ToString and @EqualsAndHashCode generate their methods")
  void standaloneToStringAndEqualsAndHashCode() {
    TypeModel type =
        infer(
            "com.example.Point",
            """
            package com.example;
            import lombok.EqualsAndHashCode;
            import lombok.ToString;
            @ToString
            @EqualsAndHashCode
            public class Point {
              private int x;
            }
            """);

    assertThat(lombokMethods(type))
        .extracting(m -> m.name() + ":" + m.generatedBy())
        .containsExactly(
            "toString:ToString", "equals:EqualsAndHashCode", "hashCode:EqualsAndHashCode");
  }

  @Test
  @DisplayName("class without Lombok annotations is unchanged")
  void noLombokNoChange() {
    TypeModel type =
        infer(
            "com.example.Plain",
            """
            package com.example;
            public class Plain {
              private final String value;
              Plain(String value) { this.value = value; }
            }
            """);

    assertThat(type.methods()).isEmpty();
    assertThat(type.constructors()).hasSize(1);
    assertThat(type.constructors().get(0).origin()).isEqualTo(MemberOrigin.SOURCE);
  }

  @Test
  @DisplayName("@Data on a class with a nested type named Data still generates the Lombok members")
  void dataAnnotationWithNestedDataType() {
    TypeModel response =
        infer(
            "com.example.dto.ApiResponse",
            """
            package com.example.dto;
            import lombok.Data;
            @Data
            public class ApiResponse {
              private String code;
              private Data data;
              public static class Data { private String name; }
            }
            """);

    assertThat(names(lombokMethods(response)))
        .contains("getCode", "setCode", "getData", "setData", "toString");
    assertThat(response.annotations().get(0).qualifiedName()).isEqualTo("lombok.Data");
    assertThat(response.fields().get(1).type().qualifiedName())
        .isEqualTo("com.example.dto.ApiResponse.Data");
  }
}
