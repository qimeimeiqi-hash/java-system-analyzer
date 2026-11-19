package com.example.javaanalyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.example.javaanalyzer.ir.FieldModel;
import com.example.javaanalyzer.ir.MemberOrigin;
import com.example.javaanalyzer.ir.MethodModel;
import com.example.javaanalyzer.ir.TypeKind;
import com.example.javaanalyzer.ir.TypeModel;
import com.example.javaanalyzer.ir.TypeRef;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureExtractorTest {

  @TempDir Path projectRoot;

  private static final String ORDER_SERVICE =
      """
      package com.example.service;

      import com.example.domain.Order;
      import java.util.List;
      import java.util.Map;

      /**
       * Handles orders.
       *
       * @since 1.0
       */
      @Deprecated(since = "2.0", forRemoval = true)
      public class OrderService extends BaseService implements Auditable, Cloneable {
        private static final int LIMIT = 10;
        /** Cache of orders. */
        private final Map<Long, List<Order>> cache = null, other;
        private int[] counts;

        public OrderService(Order seed) {}

        /** Finds orders. */
        public List<Order> find(String keyword, @Deprecated int page) { return null; }

        void process() {}

        // Nested status holder
        static class Status {}
      }
      """;

  private TypeModel extract(String qualifiedName, String... sources) {
    return AnalysisTestSupport.contextOf(projectRoot, sources).type(qualifiedName);
  }

  @Test
  @DisplayName("Type header: kind, modifiers, Javadoc, annotations with raw attributes, hierarchy")
  void extractsTypeHeader() {
    TypeModel type = extract("com.example.service.OrderService", ORDER_SERVICE);

    assertThat(type.kind()).isEqualTo(TypeKind.CLASS);
    assertThat(type.simpleName()).isEqualTo("OrderService");
    assertThat(type.packageName()).isEqualTo("com.example.service");
    assertThat(type.sourceFile()).isEqualTo("src/main/java/com/example/service/OrderService.java");
    assertThat(type.modifiers()).containsExactly("public");
    assertThat(type.javadoc()).startsWith("Handles orders.").contains("@since 1.0");
    assertThat(type.annotations())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.name()).isEqualTo("Deprecated");
              assertThat(a.qualifiedName()).isEqualTo("java.lang.Deprecated");
              assertThat(a.attributes())
                  .containsEntry("since", "\"2.0\"")
                  .containsEntry("forRemoval", "true");
            });
    assertThat(type.superClass().text()).isEqualTo("BaseService");
    assertThat(type.superClass().qualifiedName()).isNull();
    assertThat(type.interfaces())
        .extracting(TypeRef::text)
        .containsExactly("Auditable", "Cloneable");
    assertThat(type.imports())
        .containsExactly("com.example.domain.Order", "java.util.List", "java.util.Map");
  }

  @Test
  @DisplayName(
      "Fields: one entry per variable, generics resolved, initializer flag and Javadoc kept")
  void extractsFields() {
    TypeModel type = extract("com.example.service.OrderService", ORDER_SERVICE);

    assertThat(type.fields())
        .extracting(FieldModel::name, f -> f.type().text(), FieldModel::initialized)
        .containsExactly(
            tuple("LIMIT", "int", true),
            tuple("cache", "Map<Long, List<Order>>", true),
            tuple("other", "Map<Long, List<Order>>", false),
            tuple("counts", "int[]", false));
    FieldModel cache = type.fields().get(1);
    assertThat(cache.modifiers()).containsExactly("private", "final");
    assertThat(cache.javadoc()).isEqualTo("Cache of orders.");
    assertThat(cache.type().qualifiedName()).isEqualTo("java.util.Map");
    TypeRef listOfOrders = cache.type().typeArguments().get(1);
    assertThat(listOfOrders.qualifiedName()).isEqualTo("java.util.List");
    assertThat(listOfOrders.typeArguments().get(0).qualifiedName())
        .isEqualTo("com.example.domain.Order");
    assertThat(type.fields().get(3).type().qualifiedName()).isEqualTo("int[]");
  }

  @Test
  @DisplayName("Constructors and methods with parameters, annotations, Javadoc and SOURCE origin")
  void extractsConstructorsAndMethods() {
    TypeModel type = extract("com.example.service.OrderService", ORDER_SERVICE);

    assertThat(type.constructors())
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.name()).isEqualTo("OrderService");
              assertThat(c.returnType()).isNull();
              assertThat(c.parameters()).extracting(p -> p.type().text()).containsExactly("Order");
            });
    assertThat(type.methods()).extracting(MethodModel::name).containsExactly("find", "process");
    MethodModel find = type.methods().get(0);
    assertThat(find.returnType().text()).isEqualTo("List<Order>");
    assertThat(find.javadoc()).isEqualTo("Finds orders.");
    assertThat(find.origin()).isEqualTo(MemberOrigin.SOURCE);
    assertThat(find.generatedBy()).isNull();
    assertThat(find.parameters())
        .extracting(p -> p.name(), p -> p.type().text(), p -> p.annotations().size())
        .containsExactly(tuple("keyword", "String", 0), tuple("page", "int", 1));
  }

  @Test
  @DisplayName("Nested types become separate IR types with a line comment kept")
  void extractsNestedTypes() {
    TypeModel nested = extract("com.example.service.OrderService.Status", ORDER_SERVICE);

    assertThat(nested.simpleName()).isEqualTo("Status");
    assertThat(nested.modifiers()).containsExactly("static");
    assertThat(nested.comment()).isEqualTo("Nested status holder");
    assertThat(nested.javadoc()).isNull();
  }

  @Test
  @DisplayName("Interfaces list extended types as interfaces; enums and records are recognized")
  void extractsOtherKinds() {
    AnalysisContext context =
        AnalysisTestSupport.contextOf(
            projectRoot,
            """
            package com.example.domain;
            public interface OrderRepository extends Repository<Order, Long>, Auditable {}
            """,
            """
            package com.example.domain;
            public enum OrderStatus implements Labeled { NEW, PAID }
            """,
            """
            package com.example.domain;
            public record Money(@Positive long amount, String currency) {}
            """);

    TypeModel repository = context.type("com.example.domain.OrderRepository");
    assertThat(repository.kind()).isEqualTo(TypeKind.INTERFACE);
    assertThat(repository.superClass()).isNull();
    assertThat(repository.interfaces())
        .extracting(TypeRef::text)
        .containsExactly("Repository<Order, Long>", "Auditable");
    assertThat(context.type("com.example.domain.OrderStatus").kind()).isEqualTo(TypeKind.ENUM);
    TypeModel money = context.type("com.example.domain.Money");
    assertThat(money.kind()).isEqualTo(TypeKind.RECORD);
    assertThat(money.fields())
        .extracting(FieldModel::name, f -> f.type().text(), f -> f.annotations().size())
        .containsExactly(tuple("amount", "long", 1), tuple("currency", "String", 0));
  }

  @Test
  @DisplayName("Non-Javadoc comments of fields and methods, and comments in bodies, are kept")
  void keepsMemberAndBodyComments() {
    TypeModel type =
        extract(
            "com.example.Pricing",
            """
            package com.example;
            public class Pricing {
              // Tax rate in percent
              private int rate;
              /** Javadoc only. */
              private int other;
              /* Applies the rate. */
              int apply(int amount) {
                // round down first
                int base = amount / 100; /* integer division */
                return base * rate;
              }
              Pricing() {
                // default rate
                rate = 10;
              }
              int noComments() { return 0; }
            }
            """);

    FieldModel rate = type.fields().get(0);
    assertThat(rate.comment()).isEqualTo("Tax rate in percent");
    assertThat(rate.javadoc()).isNull();
    FieldModel other = type.fields().get(1);
    assertThat(other.comment()).isNull();
    assertThat(other.javadoc()).isEqualTo("Javadoc only.");

    MethodModel apply = type.methods().get(0);
    assertThat(apply.comment()).isEqualTo("Applies the rate.");
    assertThat(apply.bodyComments()).containsExactly("round down first", "integer division");
    assertThat(type.constructors().get(0).bodyComments()).containsExactly("default rate");
    MethodModel noComments = type.methods().get(1);
    assertThat(noComments.comment()).isNull();
    assertThat(noComments.bodyComments()).isEmpty();
  }
}
