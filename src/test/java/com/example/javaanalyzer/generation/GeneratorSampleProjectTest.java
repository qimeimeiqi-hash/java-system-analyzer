package com.example.javaanalyzer.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.analysis.ProjectAnalyzer;
import com.example.javaanalyzer.analysis.plugin.mybatis.MyBatisPlugin;
import com.example.javaanalyzer.analysis.plugin.springboot.SpringBootPlugin;
import com.example.javaanalyzer.analysis.plugin.view.ViewTemplatePlugin;
import com.example.javaanalyzer.generation.diagram.DependencyDiagramGenerator;
import com.example.javaanalyzer.generation.diagram.ErDiagramGenerator;
import com.example.javaanalyzer.generation.diagram.ViewTransitionDiagramGenerator;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.io.IrReader;
import com.example.javaanalyzer.ir.io.IrWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Generators on the sample projects, through the saved IR only: analysis, {@link IrWriter}, {@link
 * IrReader}, then generation (architecture principle 2).
 */
class GeneratorSampleProjectTest {

  @TempDir Path irDir;

  private ProjectModel savedIr(String sample) {
    ProjectModel analyzed =
        new ProjectAnalyzer(
                List.of(new SpringBootPlugin(), new MyBatisPlugin(), new ViewTemplatePlugin()))
            .analyze(Path.of("src/test/resources/sample-projects", sample));
    IrWriter.write(analyzed, irDir);
    return IrReader.read(irDir);
  }

  @Test
  @DisplayName("lombok-jpa-demo: API list, ER diagram and the Lombok constructor injection")
  void lombokJpaDemo() {
    ProjectModel model = savedIr("lombok-jpa-demo");

    assertThat(ApiListGenerator.table(model, null).texts())
        .containsExactly(
            List.of(
                "OrderController",
                "create",
                "POST",
                "/api/orders",
                "CreateOrderRequest",
                "OrderResponse"),
            List.of("OrderController", "get", "GET", "/api/orders/{id}", "-", "OrderResponse"));

    String er = ErDiagramGenerator.diagram(model.entities()).orElseThrow().code();
    assertThat(er)
        .contains("e1[\"Customer（customers）継承：BaseEntity\"]")
        .contains("e2[\"Product（テーブル名未指定）継承：BaseEntity\"]")
        .contains("e3[\"PurchaseOrder（purchase_orders）継承：BaseEntity\"]")
        .contains("  e1 ||--o{ e3 : \"orders / customer（customer_id）\"\n")
        .contains("  e3 }o--o{ e2 : \"products / orders（order_products）\"\n")
        .doesNotContain("[\"BaseEntity（")
        .doesNotContain("Address（");

    ClassOverview service =
        ClassOverviewGenerator.of(model, "com.example.shop.service.OrderService").orElseThrow();
    Section dependencies =
        service.sections().stream().filter(s -> s.title().equals("依存先")).findFirst().orElseThrow();
    assertThat(dependencies.table().texts())
        .extracting(row -> row.get(0) + " " + row.get(5))
        .containsExactlyInAnyOrder(
            "purchaseOrderRepository Lombok（@RequiredArgsConstructor）",
            "customerRepository Lombok（@RequiredArgsConstructor）");
  }

  @Test
  @DisplayName(
      "circular-dependency-demo: the cycle is highlighted and the service.impl layout kept")
  void circularDependencyDemo() {
    ProjectModel model = savedIr("circular-dependency-demo");

    String components = DependencyDiagramGenerator.components(model, null).diagram().code();
    // AuditService (n2) and InvoiceServiceImpl (n3) form the cycle; the controller reaches the
    // implementation through its single-implementation interface (dashed).
    assertThat(components)
        .contains(
            """
              subgraph p2["service.impl"]
                n2["AuditService"]
                n3["InvoiceServiceImpl"]
              end
            """)
        .contains("  n2 -.-> n3\n  n3 --> n1\n  n3 --> n2\n  n4 -.-> n3\n")
        .endsWith("  class n2,n3 cycle\n");
    assertThat(DependencyDiagramGenerator.packages(model).orElseThrow().code())
        .contains("\"service.impl\"");
    assertThat(DependencyDiagramGenerator.layers(model).orElseThrow().code())
        .contains("controller[\"Controller（1）\"]");
  }

  @Test
  @DisplayName("mybatis-demo, spring-config-demo and thymeleaf-demo produce their screens' content")
  void otherSamples() {
    ProjectModel library = savedIr("mybatis-demo");
    assertThat(MyBatisReportGenerator.mappers(library))
        .extracting(MapperReport::title)
        .contains("BookMapper", "AuthorMapper");

    ProjectModel mail = savedIr("spring-config-demo");
    assertThat(ConfigurationReportGenerator.beans(mail).texts())
        .containsExactly(
            List.of("MailApplication", "clock", "未指定", "Clock", "-"),
            List.of("DataConfig", "dataSource", "mainDataSource", "DataSource", "-"),
            List.of("MailConfig", "smsNotifier", "未指定", "Notifier", "-"));

    ProjectModel clinic = savedIr("thymeleaf-demo");
    assertThat(ViewReportGenerator.screens(clinic).texts())
        .anyMatch(row -> row.get(3).contains("PetController.list"));
    assertThat(ViewTransitionDiagramGenerator.diagram(clinic).orElseThrow().code())
        .contains("-.->");
  }
}
