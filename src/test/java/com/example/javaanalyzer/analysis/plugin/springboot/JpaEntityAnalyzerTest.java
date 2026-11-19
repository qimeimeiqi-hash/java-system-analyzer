package com.example.javaanalyzer.analysis.plugin.springboot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.example.javaanalyzer.analysis.AnalysisContext;
import com.example.javaanalyzer.analysis.AnalysisTestSupport;
import com.example.javaanalyzer.ir.ColumnModel;
import com.example.javaanalyzer.ir.EntityKind;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.JoinColumnModel;
import com.example.javaanalyzer.ir.JoinTableModel;
import com.example.javaanalyzer.ir.RelationKind;
import com.example.javaanalyzer.ir.RelationModel;
import com.example.javaanalyzer.ir.TypeRef;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JpaEntityAnalyzerTest {

  @TempDir Path projectRoot;

  private List<EntityModel> analyze(String... sources) {
    AnalysisContext context = AnalysisTestSupport.contextOf(projectRoot, sources);
    new JpaEntityAnalyzer().analyze(context);
    return context.toProjectModel().entities();
  }

  private static EntityModel entity(List<EntityModel> entities, String typeName) {
    return entities.stream()
        .filter(e -> e.typeName().equals(typeName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No entity " + typeName + " in " + entities));
  }

  private static ColumnModel column(EntityModel entity, String fieldName) {
    return entity.columns().stream()
        .filter(c -> c.fieldName().equals(fieldName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No column " + fieldName + " in " + entity));
  }

  private static RelationModel relation(EntityModel entity, String fieldName) {
    return entity.relations().stream()
        .filter(r -> r.fieldName().equals(fieldName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No relation " + fieldName + " in " + entity));
  }

  @Test
  @DisplayName("Jakarta @Entity with @Table name and schema is extracted with columns in order")
  void extractsJakartaEntityWithTable() {
    List<EntityModel> entities =
        analyze(
            """
            package com.example.domain;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.Table;

            @Entity(name = "Customer")
            @Table(name = "customers", schema = "sales")
            public class Customer {
              @Id private Long id;
              private String name;
            }
            """);

    assertThat(entities).hasSize(1);
    EntityModel customer = entities.get(0);
    assertThat(customer.typeName()).isEqualTo("com.example.domain.Customer");
    assertThat(customer.kind()).isEqualTo(EntityKind.ENTITY);
    assertThat(customer.entityName()).isEqualTo("Customer");
    assertThat(customer.tableName()).isEqualTo("customers");
    assertThat(customer.schema()).isEqualTo("sales");
    assertThat(customer.superType()).isNull();
    assertThat(customer.relations()).isEmpty();
    assertThat(customer.columns())
        .containsExactly(
            new ColumnModel(
                "id",
                new TypeRef("Long", "java.lang.Long", List.of()),
                null,
                true,
                null,
                null,
                null,
                null,
                null,
                false),
            new ColumnModel(
                "name",
                new TypeRef("String", "java.lang.String", List.of()),
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                false));
  }

  @Test
  @DisplayName("Javax (Spring Boot 2) @Entity without @Table has null entity/table/schema names")
  void extractsJavaxEntityWithoutTable() {
    List<EntityModel> entities =
        analyze(
            """
            package com.example.domain;

            import javax.persistence.Entity;
            import javax.persistence.Id;

            @Entity
            public class OrderLine {
              @Id private Long id;
            }
            """);

    assertThat(entities).hasSize(1);
    EntityModel line = entities.get(0);
    assertThat(line.typeName()).isEqualTo("com.example.domain.OrderLine");
    assertThat(line.kind()).isEqualTo(EntityKind.ENTITY);
    assertThat(line.entityName()).isNull();
    assertThat(line.tableName()).isNull();
    assertThat(line.schema()).isNull();
    assertThat(line.columns()).extracting(ColumnModel::fieldName).containsExactly("id");
    assertThat(column(line, "id").id()).isTrue();
  }

  @Test
  @DisplayName(
      "Wildcard import jakarta.persistence.* is recognized for entity and column annotations")
  void recognizesWildcardImport() {
    List<EntityModel> entities =
        analyze(
            """
            package com.example.domain;

            import jakarta.persistence.*;

            @Entity
            @Table(name = "products")
            public class Product {
              @Id
              @GeneratedValue(strategy = GenerationType.IDENTITY)
              private Long id;

              @Column(name = "product_code", nullable = false, length = 20, unique = true)
              private String code;
            }
            """);

    EntityModel product = entity(entities, "com.example.domain.Product");
    assertThat(product.tableName()).isEqualTo("products");
    assertThat(column(product, "id").generationStrategy()).isEqualTo("IDENTITY");
    ColumnModel code = column(product, "code");
    assertThat(code.columnName()).isEqualTo("product_code");
    assertThat(code.nullable()).isFalse();
    assertThat(code.length()).isEqualTo(20);
    assertThat(code.unique()).isTrue();
  }

  @Test
  @DisplayName("Fully qualified annotation names are recognized without imports")
  void recognizesFullyQualifiedAnnotations() {
    List<EntityModel> entities =
        analyze(
            """
            package com.example.domain;

            @jakarta.persistence.Entity
            @jakarta.persistence.Table(name = "tags")
            public class Tag {
              @jakarta.persistence.Id private Long id;
            }
            """);

    EntityModel tag = entity(entities, "com.example.domain.Tag");
    assertThat(tag.tableName()).isEqualTo("tags");
    assertThat(column(tag, "id").id()).isTrue();
  }

  @Test
  @DisplayName("@GeneratedValue without strategy yields empty string; absent yields null")
  void generatedValueWithoutStrategy() {
    List<EntityModel> entities =
        analyze(
            """
            package com.example.domain;

            import jakarta.persistence.Entity;
            import jakarta.persistence.GeneratedValue;
            import jakarta.persistence.Id;

            @Entity
            public class Ticket {
              @Id @GeneratedValue private Long id;
              private String title;
            }
            """);

    EntityModel ticket = entity(entities, "com.example.domain.Ticket");
    assertThat(column(ticket, "id").generationStrategy()).isEmpty();
    assertThat(column(ticket, "title").generationStrategy()).isNull();
  }

  @Test
  @DisplayName("@Column attributes not written stay null and constant names are not resolved")
  void columnAttributesNotWrittenStayNull() {
    List<EntityModel> entities =
        analyze(
            """
            package com.example.domain;

            import jakarta.persistence.Column;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.Table;

            @Entity
            @Table(name = Memo.TABLE)
            public class Memo {
              static final String TABLE = "memos";
              static final String BODY = "body_text";

              @Id private Long id;

              @Column(length = 4000)
              private String text;

              @Column(name = BODY)
              private String body;
            }
            """);

    EntityModel memo = entity(entities, "com.example.domain.Memo");
    assertThat(memo.tableName()).isNull();
    ColumnModel text = column(memo, "text");
    assertThat(text.columnName()).isNull();
    assertThat(text.length()).isEqualTo(4000);
    assertThat(text.nullable()).isNull();
    assertThat(text.unique()).isNull();
    assertThat(column(memo, "body").columnName()).isNull();
  }

  @Test
  @DisplayName(
      "@Enumerated records the written EnumType, empty string without value, null if absent")
  void enumeratedValues() {
    List<EntityModel> entities =
        analyze(
            """
            package com.example.domain;

            import jakarta.persistence.Entity;
            import jakarta.persistence.EnumType;
            import jakarta.persistence.Enumerated;
            import jakarta.persistence.Id;

            @Entity
            public class Account {
              @Id private Long id;

              @Enumerated(EnumType.STRING)
              private Status status;

              @Enumerated
              private Status previousStatus;

              private Status rawStatus;

              public enum Status { ACTIVE, CLOSED }
            }
            """);

    EntityModel account = entity(entities, "com.example.domain.Account");
    assertThat(column(account, "status").enumType()).isEqualTo("STRING");
    assertThat(column(account, "previousStatus").enumType()).isEmpty();
    assertThat(column(account, "rawStatus").enumType()).isNull();
    assertThat(column(account, "status").type().qualifiedName())
        .isEqualTo("com.example.domain.Account.Status");
  }

  @Test
  @DisplayName("@Transient, transient modifier and static fields are not mapped")
  void skipsTransientAndStaticFields() {
    List<EntityModel> entities =
        analyze(
            """
            package com.example.domain;

            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.Transient;

            @Entity
            public class Person {
              private static final long serialVersionUID = 1L;
              private static int counter;

              @Id private Long id;

              @Transient private String displayName;

              private transient String cache;

              private String email;
            }
            """);

    EntityModel person = entity(entities, "com.example.domain.Person");
    assertThat(person.columns()).extracting(ColumnModel::fieldName).containsExactly("id", "email");
  }

  @Test
  @DisplayName("Bidirectional OneToMany(mappedBy) / ManyToOne(@JoinColumn) pair is extracted")
  void bidirectionalOneToManyManyToOne() {
    List<EntityModel> entities =
        analyze(
            """
            package com.example.domain;

            import jakarta.persistence.CascadeType;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.OneToMany;
            import java.util.ArrayList;
            import java.util.List;

            @Entity
            public class PurchaseOrder {
              @Id private Long id;

              @OneToMany(mappedBy = "order", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
              private List<OrderItem> items = new ArrayList<>();
            }
            """,
            """
            package com.example.domain;

            import jakarta.persistence.Entity;
            import jakarta.persistence.FetchType;
            import jakarta.persistence.Id;
            import jakarta.persistence.JoinColumn;
            import jakarta.persistence.ManyToOne;

            @Entity
            public class OrderItem {
              @Id private Long id;

              @ManyToOne(optional = false, fetch = FetchType.LAZY)
              @JoinColumn(name = "order_id", referencedColumnName = "id", nullable = false)
              private PurchaseOrder order;
            }
            """);

    EntityModel order = entity(entities, "com.example.domain.PurchaseOrder");
    assertThat(order.columns()).extracting(ColumnModel::fieldName).containsExactly("id");
    RelationModel items = relation(order, "items");
    assertThat(items.kind()).isEqualTo(RelationKind.ONE_TO_MANY);
    assertThat(items.collection()).isTrue();
    assertThat(items.targetType())
        .isEqualTo(new TypeRef("OrderItem", "com.example.domain.OrderItem", List.of()));
    assertThat(items.mappedBy()).isEqualTo("order");
    assertThat(items.owningSide()).isFalse();
    assertThat(items.optional()).isNull();
    assertThat(items.fetch()).isNull();
    assertThat(items.cascade()).containsExactly("PERSIST", "MERGE");
    assertThat(items.joinColumns()).isEmpty();
    assertThat(items.joinTable()).isNull();

    EntityModel item = entity(entities, "com.example.domain.OrderItem");
    assertThat(item.columns()).extracting(ColumnModel::fieldName).containsExactly("id");
    RelationModel orderRelation = relation(item, "order");
    assertThat(orderRelation.kind()).isEqualTo(RelationKind.MANY_TO_ONE);
    assertThat(orderRelation.collection()).isFalse();
    assertThat(orderRelation.targetType())
        .isEqualTo(new TypeRef("PurchaseOrder", "com.example.domain.PurchaseOrder", List.of()));
    assertThat(orderRelation.mappedBy()).isNull();
    assertThat(orderRelation.owningSide()).isTrue();
    assertThat(orderRelation.optional()).isFalse();
    assertThat(orderRelation.fetch()).isEqualTo("LAZY");
    assertThat(orderRelation.cascade()).isEmpty();
    assertThat(orderRelation.joinColumns())
        .containsExactly(new JoinColumnModel("order_id", "id", false));
  }

  @Test
  @DisplayName("ManyToMany with @JoinTable records join and inverse join columns")
  void manyToManyWithJoinTable() {
    List<EntityModel> entities =
        analyze(
            """
            package com.example.domain;

            import jakarta.persistence.*;
            import java.util.Set;

            @Entity
            public class Student {
              @Id private Long id;

              @ManyToMany
              @JoinTable(
                  name = "student_course",
                  joinColumns = @JoinColumn(name = "student_id"),
                  inverseJoinColumns = {
                    @JoinColumn(name = "course_id", referencedColumnName = "id"),
                    @JoinColumn(name = "course_term", nullable = true)
                  })
              private Set<Course> courses;
            }
            """,
            """
            package com.example.domain;

            import jakarta.persistence.*;
            import java.util.Set;

            @Entity
            public class Course {
              @Id private Long id;

              @ManyToMany(mappedBy = "courses")
              private Set<Student> students;
            }
            """);

    RelationModel courses = relation(entity(entities, "com.example.domain.Student"), "courses");
    assertThat(courses.kind()).isEqualTo(RelationKind.MANY_TO_MANY);
    assertThat(courses.collection()).isTrue();
    assertThat(courses.targetType().qualifiedName()).isEqualTo("com.example.domain.Course");
    assertThat(courses.owningSide()).isTrue();
    assertThat(courses.joinTable())
        .isEqualTo(
            new JoinTableModel(
                "student_course",
                List.of(new JoinColumnModel("student_id", null, null)),
                List.of(
                    new JoinColumnModel("course_id", "id", null),
                    new JoinColumnModel("course_term", null, true))));

    RelationModel students = relation(entity(entities, "com.example.domain.Course"), "students");
    assertThat(students.kind()).isEqualTo(RelationKind.MANY_TO_MANY);
    assertThat(students.mappedBy()).isEqualTo("courses");
    assertThat(students.owningSide()).isFalse();
    assertThat(students.joinTable()).isNull();
    assertThat(students.targetType().qualifiedName()).isEqualTo("com.example.domain.Student");
  }

  @Test
  @DisplayName("OneToOne with @JoinColumns records multiple join columns and is not a collection")
  void oneToOneWithJoinColumns() {
    List<EntityModel> entities =
        analyze(
            """
            package com.example.domain;

            import jakarta.persistence.*;

            @Entity
            public class UserAccount {
              @Id private Long id;

              @OneToOne(cascade = CascadeType.ALL, optional = true)
              @JoinColumns({
                @JoinColumn(name = "profile_id", referencedColumnName = "id"),
                @JoinColumn(name = "profile_version", referencedColumnName = "version")
              })
              private Profile profile;
            }
            """,
            """
            package com.example.domain;

            import jakarta.persistence.*;

            @Entity
            public class Profile {
              @Id private Long id;
            }
            """);

    RelationModel profile = relation(entity(entities, "com.example.domain.UserAccount"), "profile");
    assertThat(profile.kind()).isEqualTo(RelationKind.ONE_TO_ONE);
    assertThat(profile.collection()).isFalse();
    assertThat(profile.targetType())
        .isEqualTo(new TypeRef("Profile", "com.example.domain.Profile", List.of()));
    assertThat(profile.optional()).isTrue();
    assertThat(profile.cascade()).containsExactly("ALL");
    assertThat(profile.joinColumns())
        .containsExactly(
            new JoinColumnModel("profile_id", "id", null),
            new JoinColumnModel("profile_version", "version", null));
  }

  @Test
  @DisplayName("targetEntity attribute overrides the declared field type")
  void targetEntityOverridesFieldType() {
    List<EntityModel> entities =
        analyze(
            """
            package com.example.domain;

            import jakarta.persistence.*;
            import java.util.Collection;

            @Entity
            public class Library {
              @Id private Long id;

              @OneToMany(targetEntity = Book.class)
              private Collection items;

              @ManyToOne(targetEntity = Library.class)
              private Object parent;
            }
            """,
            """
            package com.example.domain;

            import jakarta.persistence.*;

            @Entity
            public class Book {
              @Id private Long id;
            }
            """);

    EntityModel library = entity(entities, "com.example.domain.Library");
    assertThat(relation(library, "items").targetType())
        .isEqualTo(new TypeRef("Book", "com.example.domain.Book", List.of()));
    assertThat(relation(library, "items").collection()).isTrue();
    assertThat(relation(library, "parent").targetType())
        .isEqualTo(new TypeRef("Library", "com.example.domain.Library", List.of()));
  }

  @Test
  @DisplayName("Map collections use the value type argument as target")
  void mapCollectionUsesValueType() {
    List<EntityModel> entities =
        analyze(
            """
            package com.example.domain;

            import jakarta.persistence.*;
            import java.util.Map;

            @Entity
            public class Warehouse {
              @Id private Long id;

              @OneToMany(mappedBy = "warehouse")
              @MapKey(name = "sku")
              private Map<String, Stock> stocks;
            }
            """,
            """
            package com.example.domain;

            import jakarta.persistence.*;

            @Entity
            public class Stock {
              @Id private Long id;
              @ManyToOne private Warehouse warehouse;
            }
            """);

    RelationModel stocks = relation(entity(entities, "com.example.domain.Warehouse"), "stocks");
    assertThat(stocks.targetType())
        .isEqualTo(new TypeRef("Stock", "com.example.domain.Stock", List.of()));
    RelationModel warehouse = relation(entity(entities, "com.example.domain.Stock"), "warehouse");
    assertThat(warehouse.joinColumns()).isEmpty();
    assertThat(warehouse.owningSide()).isTrue();
  }

  @Test
  @DisplayName("@Embeddable is extracted and @Embedded / @EmbeddedId columns are flagged")
  void embeddableAndEmbedded() {
    List<EntityModel> entities =
        analyze(
            """
            package com.example.domain;

            import jakarta.persistence.Embeddable;

            @Embeddable
            public class Address {
              private String city;
              private String street;
            }
            """,
            """
            package com.example.domain;

            import jakarta.persistence.Embeddable;

            @Embeddable
            public class ShipmentId {
              private Long orderId;
              private Integer seq;
            }
            """,
            """
            package com.example.domain;

            import jakarta.persistence.*;

            @Entity
            public class Shipment {
              @EmbeddedId private ShipmentId id;

              @Embedded private Address destination;
            }
            """);

    EntityModel address = entity(entities, "com.example.domain.Address");
    assertThat(address.kind()).isEqualTo(EntityKind.EMBEDDABLE);
    assertThat(address.columns())
        .extracting(ColumnModel::fieldName)
        .containsExactly("city", "street");
    assertThat(entity(entities, "com.example.domain.ShipmentId").kind())
        .isEqualTo(EntityKind.EMBEDDABLE);

    EntityModel shipment = entity(entities, "com.example.domain.Shipment");
    ColumnModel id = column(shipment, "id");
    assertThat(id.id()).isTrue();
    assertThat(id.embedded()).isTrue();
    assertThat(id.type().qualifiedName()).isEqualTo("com.example.domain.ShipmentId");
    ColumnModel destination = column(shipment, "destination");
    assertThat(destination.id()).isFalse();
    assertThat(destination.embedded()).isTrue();
  }

  @Test
  @DisplayName("@MappedSuperclass is extracted and the subclass entity records its superType")
  void mappedSuperclassAndSubclass() {
    List<EntityModel> entities =
        analyze(
            """
            package com.example.domain;

            import jakarta.persistence.*;
            import java.time.LocalDateTime;

            @MappedSuperclass
            public abstract class BaseEntity {
              @Id @GeneratedValue(strategy = GenerationType.SEQUENCE) protected Long id;
              @Version protected Long version;
              protected LocalDateTime createdAt;
            }
            """,
            """
            package com.example.domain;

            import jakarta.persistence.*;

            @Entity
            public class Invoice extends BaseEntity {
              private String number;
            }
            """);

    EntityModel base = entity(entities, "com.example.domain.BaseEntity");
    assertThat(base.kind()).isEqualTo(EntityKind.MAPPED_SUPERCLASS);
    assertThat(base.columns())
        .extracting(ColumnModel::fieldName)
        .containsExactly("id", "version", "createdAt");
    assertThat(column(base, "id").generationStrategy()).isEqualTo("SEQUENCE");
    assertThat(column(base, "createdAt").type())
        .isEqualTo(new TypeRef("LocalDateTime", "java.time.LocalDateTime", List.of()));

    EntityModel invoice = entity(entities, "com.example.domain.Invoice");
    assertThat(invoice.kind()).isEqualTo(EntityKind.ENTITY);
    assertThat(invoice.superType())
        .isEqualTo(new TypeRef("BaseEntity", "com.example.domain.BaseEntity", List.of()));
    assertThat(invoice.columns()).extracting(ColumnModel::fieldName).containsExactly("number");
  }

  @Test
  @DisplayName("A project-local annotation named Entity does not make a class a JPA entity")
  void projectLocalEntityAnnotationIsIgnored() {
    List<EntityModel> entities =
        analyze(
            """
            package com.example;

            public @interface Entity {}
            """,
            """
            package com.example.model;

            import com.example.Entity;

            @Entity
            public class Fake {
              private Long id;
            }
            """);

    assertThat(entities).isEmpty();
  }

  @Test
  @DisplayName("Classes without persistence annotations and interfaces produce no entities")
  void nonEntityClassesProduceNothing() {
    List<EntityModel> entities =
        analyze(
            """
            package com.example.service;

            import org.springframework.stereotype.Service;

            @Service
            public class OrderService {
              private String name;
            }
            """,
            """
            package com.example.service;

            public interface Greeter {
              String greet();
            }
            """);

    assertThat(entities).isEmpty();
  }

  @Test
  @DisplayName("A record annotated @Embeddable is extracted with its components as columns")
  void embeddableRecordComponentsAreColumns() {
    List<EntityModel> entities =
        analyze(
            """
            package com.example.customer;
            import jakarta.persistence.Column;
            import jakarta.persistence.Embeddable;
            import jakarta.persistence.ManyToOne;
            @Embeddable
            public record Address(
                @Column(name = "city_name", nullable = false) String city,
                String zip,
                @ManyToOne Country country) {
              static final String UNUSED = "x";
            }
            """,
            """
            package com.example.customer;
            import jakarta.persistence.Entity;
            @Entity public class Country { @jakarta.persistence.Id private Long id; }
            """);

    EntityModel address = entity(entities, "com.example.customer.Address");

    assertThat(address.kind()).isEqualTo(EntityKind.EMBEDDABLE);
    assertThat(address.columns())
        .extracting(ColumnModel::fieldName, ColumnModel::columnName, ColumnModel::nullable)
        .containsExactly(tuple("city", "city_name", false), tuple("zip", null, null));
    assertThat(relation(address, "country").targetType().qualifiedName())
        .isEqualTo("com.example.customer.Country");
  }

  @Test
  @DisplayName("A record without JPA annotations is not an entity")
  void plainRecordIsNotAnEntity() {
    assertThat(analyze("package com.example; public record Point(int x, int y) {}")).isEmpty();
  }
}
