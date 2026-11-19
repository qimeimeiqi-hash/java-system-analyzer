package com.example.javaanalyzer.ir.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.example.javaanalyzer.analysis.ProjectAnalyzer;
import com.example.javaanalyzer.analysis.plugin.mybatis.MyBatisPlugin;
import com.example.javaanalyzer.analysis.plugin.springboot.SpringBootPlugin;
import com.example.javaanalyzer.ir.BeanDefinitionModel;
import com.example.javaanalyzer.ir.CallModel;
import com.example.javaanalyzer.ir.ConfigurationPropertiesModel;
import com.example.javaanalyzer.ir.DependencyModel;
import com.example.javaanalyzer.ir.DependencyResolution;
import com.example.javaanalyzer.ir.IssueKind;
import com.example.javaanalyzer.ir.MapperModel;
import com.example.javaanalyzer.ir.MapperStatementModel;
import com.example.javaanalyzer.ir.MethodModel;
import com.example.javaanalyzer.ir.PackageModel;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.StatementSource;
import com.example.javaanalyzer.ir.TypeModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end check on checked-in sample projects: sources → IR → JSON files → IR. */
class SampleProjectIrOutputTest {

  private static final String BILLING = "com.example.billing.";

  @TempDir Path irDir;

  private static ProjectModel analyzeSample(String name) {
    return new ProjectAnalyzer(List.of(new SpringBootPlugin()))
        .analyze(Path.of("src/test/resources/sample-projects", name));
  }

  private static DependencyModel dependency(ProjectModel model, String from, String member) {
    return model.dependencies().stream()
        .filter(d -> d.fromType().equals(BILLING + from) && d.memberName().equals(member))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No dependency " + from + "." + member));
  }

  @Test
  @DisplayName("Circular demo: the full chain is resolved and written as readable JSON")
  void circularDemoIsWrittenAndRestored() throws Exception {
    ProjectModel model = analyzeSample("circular-dependency-demo");

    IrWriter.write(model, irDir);

    assertThat(IrReader.read(irDir)).isEqualTo(model);
    assertThat(dependency(model, "web.InvoiceController", "invoiceService").resolvedType())
        .isEqualTo(BILLING + "service.impl.InvoiceServiceImpl");
    assertThat(
            dependency(model, "service.impl.InvoiceServiceImpl", "invoiceRepository").resolution())
        .isEqualTo(DependencyResolution.PROJECT_TYPE);
    assertThat(Files.readString(irDir.resolve("project.json")))
        .contains("SINGLE_IMPLEMENTATION", "CIRCULAR_DEPENDENCY", "InvoiceServiceImpl");
  }

  @Test
  @DisplayName("Circular demo: the cycle through the interface is the only reported cycle")
  void circularDemoReportsTheComponentCycle() {
    ProjectModel model = analyzeSample("circular-dependency-demo");

    assertThat(model.issues())
        .filteredOn(i -> i.kind() == IssueKind.CIRCULAR_DEPENDENCY)
        .hasSize(1);
    assertThat(model.issues())
        .filteredOn(i -> i.kind() == IssueKind.CIRCULAR_DEPENDENCY)
        .first()
        .satisfies(
            issue ->
                assertThat(issue.message())
                    .contains(
                        BILLING + "service.impl.AuditService",
                        BILLING + "service.impl.InvoiceServiceImpl"));
  }

  @Test
  @DisplayName("Circular demo: package dependencies follow web → service.impl → repository")
  void circularDemoPackageDependencies() {
    ProjectModel model = analyzeSample("circular-dependency-demo");

    assertThat(model.packages())
        .filteredOn(p -> p.name().equals(BILLING + "web"))
        .extracting(PackageModel::dependsOn)
        .containsExactly(List.of(BILLING + "service.impl"));
    assertThat(model.packages())
        .filteredOn(p -> p.name().equals(BILLING + "service.impl"))
        .extracting(PackageModel::dependsOn)
        .containsExactly(List.of(BILLING + "repository"));
  }

  @Test
  @DisplayName("Circular demo: the chain Controller → Service → Repository is detected")
  void circularDemoDetectsCallChain() {
    ProjectModel model = analyzeSample("circular-dependency-demo");

    assertThat(model.calls())
        .extracting(
            CallModel::fromType,
            CallModel::fromMethod,
            CallModel::targetType,
            CallModel::methodName)
        .contains(
            tuple(
                BILLING + "web.InvoiceController",
                "count",
                BILLING + "service.impl.InvoiceServiceImpl",
                "count"),
            tuple(
                BILLING + "service.impl.InvoiceServiceImpl",
                "count",
                BILLING + "repository.InvoiceRepository",
                "count"));
  }

  @Test
  @DisplayName("Lombok/JPA demo: a call through a Lombok constructor reaches the repository")
  void lombokDemoDetectsCallThroughLombokConstructor() {
    ProjectModel model = analyzeSample("lombok-jpa-demo");

    assertThat(model.calls())
        .extracting(
            CallModel::fromMethod,
            CallModel::fieldName,
            CallModel::methodName,
            CallModel::targetType,
            CallModel::line)
        .containsExactly(
            tuple(
                "find",
                "purchaseOrderRepository",
                "findById",
                "com.example.shop.repository.PurchaseOrderRepository",
                22));
  }

  @Test
  @DisplayName("Circular demo: calls are saved with the package of the caller and read back")
  void circularDemoSavesCalls() throws Exception {
    ProjectModel model = analyzeSample("circular-dependency-demo");

    IrWriter.write(model, irDir);

    assertThat(IrReader.read(irDir)).isEqualTo(model);
    assertThat(Files.readString(irDir.resolve("packages/com.example.billing.web.json")))
        .contains("\"calls\" : [ {", "\"fieldName\" : \"invoiceService\"");
  }

  private static final String LIBRARY_MAPPER = "com.example.library.mapper.";

  private static ProjectModel analyzeMyBatisSample() {
    return new ProjectAnalyzer(List.of(new SpringBootPlugin(), new MyBatisPlugin()))
        .analyze(Path.of("src/test/resources/sample-projects/mybatis-demo"));
  }

  private static MapperModel mapperOf(ProjectModel model, String interfaceName) {
    return model.mappers().stream()
        .filter(m -> interfaceName.equals(m.interfaceType()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No mapper " + interfaceName));
  }

  @Test
  @DisplayName("MyBatis demo: every mapper is listed, the one without interface last")
  void myBatisDemoListsMappers() {
    assertThat(analyzeMyBatisSample().mappers())
        .extracting(MapperModel::interfaceType, MapperModel::namespace)
        .containsExactly(
            tuple(LIBRARY_MAPPER + "AuthorMapper", null),
            tuple(LIBRARY_MAPPER + "BookMapper", LIBRARY_MAPPER + "BookMapper"),
            tuple(null, LIBRARY_MAPPER + "RemovedMapper"));
  }

  @Test
  @DisplayName("MyBatis demo: statements link to methods, leaving gaps visible on both sides")
  void myBatisDemoLinksStatementsAndMethods() {
    ProjectModel model = analyzeMyBatisSample();
    MapperModel books = mapperOf(model, LIBRARY_MAPPER + "BookMapper");

    assertThat(books.statements())
        .extracting(
            MapperStatementModel::tag,
            MapperStatementModel::id,
            MapperStatementModel::source,
            MapperStatementModel::methodName)
        .containsExactly(
            tuple("sql", "columns", StatementSource.XML, null),
            tuple("select", "findById", StatementSource.XML, "findById"),
            tuple("select", "search", StatementSource.XML, "search"),
            tuple("select", "findByLegacyName", StatementSource.XML, null),
            tuple("delete", "deleteById", StatementSource.ANNOTATION, "deleteById"));
    TypeModel bookMapper =
        model.types().stream()
            .filter(t -> t.simpleName().equals("BookMapper"))
            .findFirst()
            .orElseThrow();
    Set<String> linked =
        books.statements().stream()
            .map(MapperStatementModel::methodName)
            .collect(Collectors.toSet());
    assertThat(bookMapper.methods())
        .extracting(MethodModel::name)
        .filteredOn(name -> !linked.contains(name))
        .as("a method without SQL can be found from the IR")
        .containsExactly("countAll");
  }

  @Test
  @DisplayName("MyBatis demo: the SQL equals the text in the XML file, character for character")
  void myBatisDemoKeepsSqlAsWritten() throws Exception {
    String xml =
        Files.readString(
            Path.of(
                "src/test/resources/sample-projects/mybatis-demo/src/main/resources/mapper/BookMapper.xml"));
    MapperModel books = mapperOf(analyzeMyBatisSample(), LIBRARY_MAPPER + "BookMapper");

    String sql =
        books.statements().stream()
            .filter(s -> s.id().equals("search"))
            .findFirst()
            .orElseThrow()
            .sql();

    assertThat(xml).contains(sql);
    assertThat(sql)
        .contains(
            "<include refid=\"columns\"/>",
            "<if test='title != null and title != \"\"'>",
            "<foreach collection=\"ids\" item=\"id\" open=\"AND id IN (\" separator=\",\" close=\")\">",
            "<![CDATA[ AND price < 1000 ]]>");
  }

  @Test
  @DisplayName("MyBatis demo: BaseMapper is recorded and its methods are not inferred")
  void myBatisDemoDoesNotInferBaseMapperMethods() {
    MapperModel authors = mapperOf(analyzeMyBatisSample(), LIBRARY_MAPPER + "AuthorMapper");

    assertThat(authors.baseType().text()).isEqualTo("BaseMapper<Author>");
    assertThat(authors.statements()).isEmpty();
  }

  @Test
  @DisplayName("MyBatis demo: the service calls the mapper in the call graph")
  void myBatisDemoReachesMapperInCallGraph() {
    assertThat(analyzeMyBatisSample().calls())
        .extracting(CallModel::fromMethod, CallModel::targetType, CallModel::methodName)
        .containsExactly(tuple("find", LIBRARY_MAPPER + "BookMapper", "findById"));
  }

  @Test
  @DisplayName("MyBatis demo: the IR survives a round trip, keeping the mapper without interface")
  void myBatisDemoSurvivesRoundTrip() throws Exception {
    ProjectModel model = analyzeMyBatisSample();

    IrWriter.write(model, irDir);

    assertThat(IrReader.read(irDir)).isEqualTo(model);
    assertThat(Files.readString(irDir.resolve("project.json"))).contains("RemovedMapper");
    assertThat(Files.readString(irDir.resolve("packages/com.example.library.mapper.json")))
        .contains("findById")
        .doesNotContain("RemovedMapper");
  }

  private static final String MAIL = "com.example.mail.";

  private static ProjectModel configDemo() {
    return analyzeSample("spring-config-demo");
  }

  @Test
  @DisplayName("Config demo: @Bean definitions such as DataSource are listed")
  void configDemoListsBeanDefinitions() {
    assertThat(configDemo().beanDefinitions())
        .extracting(
            BeanDefinitionModel::declaringType,
            BeanDefinitionModel::methodName,
            bean -> bean.returnType().qualifiedName(),
            BeanDefinitionModel::beanNames)
        .containsExactly(
            tuple(MAIL + "MailApplication", "clock", "java.time.Clock", List.of()),
            tuple(
                MAIL + "config.DataConfig",
                "dataSource",
                "javax.sql.DataSource",
                List.of("mainDataSource")),
            tuple(MAIL + "config.MailConfig", "smsNotifier", MAIL + "service.Notifier", List.of()));
  }

  @Test
  @DisplayName("Config demo: both forms of @ConfigurationProperties are listed")
  void configDemoListsConfigurationProperties() {
    assertThat(configDemo().configurationProperties())
        .extracting(
            ConfigurationPropertiesModel::typeName,
            ConfigurationPropertiesModel::beanMethodName,
            ConfigurationPropertiesModel::prefix)
        .containsExactly(
            tuple(MAIL + "config.DataConfig", "dataSource", "app.datasource"),
            tuple(MAIL + "config.MailProperties", null, "app.mail"));
  }

  @Test
  @DisplayName("Config demo: Lombok and field injection of a @Configuration class are recorded")
  void configDemoRecordsConfigurationInjection() {
    assertThat(configDemo().dependencies())
        .filteredOn(d -> d.fromType().equals(MAIL + "config.MailConfig"))
        .extracting(DependencyModel::memberName, DependencyModel::resolvedType)
        .containsExactlyInAnyOrder(
            tuple("mailProperties", MAIL + "config.MailProperties"),
            tuple("auditLogger", MAIL + "config.AuditLogger"));
  }

  @Test
  @DisplayName("Config demo: List<Notifier> reaches the component implementation")
  void configDemoResolvesListInjection() {
    assertThat(configDemo().dependencies())
        .filteredOn(d -> d.memberName().equals("notifiers"))
        .extracting(DependencyModel::resolvedType, DependencyModel::resolution)
        .containsExactlyInAnyOrder(
            tuple(MAIL + "service.EmailNotifier", DependencyResolution.COLLECTION_ELEMENT),
            tuple(MAIL + "service.Notifier", DependencyResolution.COLLECTION_ELEMENT));
  }

  @Test
  @DisplayName("Config demo: the IR survives a round trip and is split per package")
  void configDemoSurvivesRoundTrip() throws Exception {
    ProjectModel model = configDemo();

    IrWriter.write(model, irDir);

    assertThat(IrReader.read(irDir)).isEqualTo(model);
    assertThat(Files.readString(irDir.resolve("packages/com.example.mail.config.json")))
        .contains("mainDataSource", "app.datasource", "app.mail");
    assertThat(Files.readString(irDir.resolve("packages/com.example.mail.service.json")))
        .doesNotContain("mainDataSource");
  }

  @Test
  @DisplayName("Lombok/JPA demo: IR including Lombok members and relations survives a round trip")
  void lombokJpaDemoSurvivesRoundTrip() {
    ProjectModel model = analyzeSample("lombok-jpa-demo");

    IrWriter.write(model, irDir);

    ProjectModel restored = IrReader.read(irDir);
    assertThat(restored).isEqualTo(model);
    assertThat(restored.entities()).isNotEmpty();
    assertThat(restored.dependencies()).isNotEmpty();
  }

  @Test
  @DisplayName("Lombok/JPA demo: output is byte-for-byte identical when written twice")
  void outputIsDeterministic() throws Exception {
    ProjectModel model = analyzeSample("lombok-jpa-demo");
    Path first = irDir.resolve("first");
    Path second = irDir.resolve("second");

    IrWriter.write(model, first);
    IrWriter.write(analyzeSample("lombok-jpa-demo"), second);

    assertThat(Files.readString(second.resolve("project.json")))
        .isEqualTo(Files.readString(first.resolve("project.json")));
    try (var files = Files.list(first.resolve("packages"))) {
      for (Path file : files.toList()) {
        assertThat(Files.readString(second.resolve("packages").resolve(file.getFileName())))
            .isEqualTo(Files.readString(file));
      }
    }
  }
}
