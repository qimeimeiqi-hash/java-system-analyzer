package com.example.javaanalyzer.generation.diagram;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.generation.Diagram;
import com.example.javaanalyzer.ir.AnalysisIssue;
import com.example.javaanalyzer.ir.BeanDefinitionModel;
import com.example.javaanalyzer.ir.CallModel;
import com.example.javaanalyzer.ir.ComponentModel;
import com.example.javaanalyzer.ir.ComponentRole;
import com.example.javaanalyzer.ir.ConfigurationPropertiesModel;
import com.example.javaanalyzer.ir.DependencyModel;
import com.example.javaanalyzer.ir.DependencyResolution;
import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.InjectionKind;
import com.example.javaanalyzer.ir.MapperModel;
import com.example.javaanalyzer.ir.PackageModel;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.RepositoryModel;
import com.example.javaanalyzer.ir.TypeModel;
import com.example.javaanalyzer.ir.TypeRef;
import com.example.javaanalyzer.ir.ViewTemplateModel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DependencyDiagramGeneratorTest {

  private static final String ROOT = "com.example.billing";
  private static final String CONTROLLER = ROOT + ".web.InvoiceController";
  private static final String SERVICE = ROOT + ".service.InvoiceService";
  private static final String SERVICE_IMPL = ROOT + ".service.impl.InvoiceServiceImpl";
  private static final String AUDIT = ROOT + ".service.impl.AuditService";
  private static final String REPOSITORY = ROOT + ".repository.InvoiceRepository";

  private static DependencyModel dependency(
      String from, String to, DependencyResolution resolution) {
    String declared = to == null ? "Unknown" : to;
    return new DependencyModel(
        from,
        "member",
        InjectionKind.CONSTRUCTOR,
        new TypeRef(declared.substring(declared.lastIndexOf('.') + 1), to, List.of()),
        resolution == DependencyResolution.AMBIGUOUS ? null : to,
        resolution);
  }

  private static ProjectModel model(
      List<ComponentModel> components,
      List<DependencyModel> dependencies,
      List<PackageModel> packages) {
    List<TypeModel> types = List.of();
    List<EndpointModel> endpoints = List.of();
    List<EntityModel> entities = List.of();
    List<RepositoryModel> repositories = List.of();
    List<BeanDefinitionModel> beans = List.of();
    List<ConfigurationPropertiesModel> properties = List.of();
    List<MapperModel> mappers = List.of();
    List<ViewTemplateModel> views = List.of();
    List<CallModel> calls = List.of();
    List<AnalysisIssue> issues = List.of();
    return new ProjectModel(
        ROOT,
        types,
        components,
        endpoints,
        entities,
        repositories,
        beans,
        properties,
        mappers,
        views,
        dependencies,
        calls,
        packages,
        issues);
  }

  /** circular-dependency-demo: InvoiceServiceImpl and AuditService depend on each other. */
  private static ProjectModel billing() {
    return model(
        List.of(
            new ComponentModel(CONTROLLER, ComponentRole.CONTROLLER, List.of()),
            new ComponentModel(SERVICE_IMPL, ComponentRole.SERVICE, List.of()),
            new ComponentModel(AUDIT, ComponentRole.SERVICE, List.of()),
            new ComponentModel(REPOSITORY, ComponentRole.REPOSITORY, List.of())),
        List.of(
            dependency(CONTROLLER, SERVICE_IMPL, DependencyResolution.SINGLE_IMPLEMENTATION),
            dependency(SERVICE_IMPL, REPOSITORY, DependencyResolution.PROJECT_TYPE),
            dependency(SERVICE_IMPL, AUDIT, DependencyResolution.PROJECT_TYPE),
            dependency(AUDIT, SERVICE_IMPL, DependencyResolution.PROJECT_TYPE),
            dependency(AUDIT, null, DependencyResolution.UNRESOLVED),
            dependency(AUDIT, "org.slf4j.Logger", DependencyResolution.EXTERNAL)),
        List.of(
            new PackageModel(ROOT + ".repository", List.of(REPOSITORY), List.of()),
            new PackageModel(ROOT + ".service", List.of(SERVICE), List.of()),
            new PackageModel(
                ROOT + ".service.impl",
                List.of(AUDIT, SERVICE_IMPL),
                List.of(ROOT + ".repository")),
            new PackageModel(ROOT + ".web", List.of(CONTROLLER), List.of(ROOT + ".service.impl"))));
  }

  @Test
  @DisplayName(
      "Component diagram groups by package, dashes single-implementation edges, marks cycles")
  void componentDiagram() {
    ComponentDiagram result = DependencyDiagramGenerator.components(billing(), null);

    assertThat(result.filterRequired()).isFalse();
    assertThat(result.nodeCount()).isEqualTo(4);
    assertThat(result.diagram().id()).isEqualTo("components");
    assertThat(result.diagram().code())
        .isEqualTo(
            """
            flowchart LR
              subgraph p1["repository"]
                n1["InvoiceRepository"]
              end
              subgraph p2["service.impl"]
                n2["AuditService"]
                n3["InvoiceServiceImpl"]
              end
              subgraph p3["web"]
                n4["InvoiceController"]
              end
              n2 --> n3
              n3 --> n1
              n3 --> n2
              n4 -.-> n3
              classDef cycle stroke:#d32f2f,stroke-width:3px
              class n2,n3 cycle
            """);
  }

  @Test
  @DisplayName("Filtering by package keeps its components and their direct neighbors only")
  void filterByPackage() {
    ComponentDiagram result = DependencyDiagramGenerator.components(billing(), ROOT + ".web");

    assertThat(result.diagram().code())
        .contains("n1[\"InvoiceServiceImpl\"]")
        .contains("n2[\"InvoiceController\"]")
        .contains("n2 -.-> n1")
        .doesNotContain("InvoiceRepository")
        .doesNotContain("AuditService");
  }

  @Test
  @DisplayName("More than 50 nodes without a package filter asks for a filter instead of a diagram")
  void largeGraphRequiresFilter() {
    List<ComponentModel> components = new ArrayList<>();
    for (int i = 0; i < 51; i++) {
      components.add(new ComponentModel(ROOT + ".p.C" + i, ComponentRole.COMPONENT, List.of()));
    }
    ProjectModel large = model(components, List.of(), List.of());

    ComponentDiagram unfiltered = DependencyDiagramGenerator.components(large, null);
    assertThat(unfiltered.filterRequired()).isTrue();
    assertThat(unfiltered.diagram()).isNull();
    assertThat(unfiltered.nodeCount()).isEqualTo(51);

    ProjectModel fifty = model(components.subList(0, 50), List.of(), List.of());
    assertThat(DependencyDiagramGenerator.components(fifty, null).filterRequired()).isFalse();
  }

  @Test
  @DisplayName(
      "Package diagram uses relative names and groups packages without any dependency in a row")
  void packageDiagram() {
    Diagram diagram = DependencyDiagramGenerator.packages(billing()).orElseThrow();

    assertThat(diagram.code())
        .isEqualTo(
            """
            flowchart LR
              n1["repository"]
              n3["service.impl"]
              n4["web"]
              subgraph isolated["依存関係なし"]
                direction LR
                n2["service"]
              end
              n3 --> n1
              n4 --> n3
            """);
  }

  @Test
  @DisplayName("No group is drawn when every package has a dependency; all go in it when none has")
  void packageDiagramIsolatedGroupEdges() {
    ProjectModel connected =
        model(
            List.of(),
            List.of(),
            List.of(
                new PackageModel(ROOT + ".service", List.of(), List.of(ROOT + ".repository")),
                new PackageModel(ROOT + ".repository", List.of(), List.of())));
    ProjectModel unconnected =
        model(
            List.of(),
            List.of(),
            List.of(
                new PackageModel(ROOT, List.of(), List.of()),
                new PackageModel(ROOT + ".dto", List.of(), List.of())));

    assertThat(DependencyDiagramGenerator.packages(connected).orElseThrow().code())
        .doesNotContain("subgraph");
    assertThat(DependencyDiagramGenerator.packages(unconnected).orElseThrow().code())
        .isEqualTo(
            """
            flowchart LR
              subgraph isolated["依存関係なし"]
                direction LR
                n1["com.example.billing"]
                n2["dto"]
                n1 ~~~ n2
              end
            """);
  }

  @Test
  @DisplayName("Packages without dependencies are laid out four per row with invisible links")
  void packageDiagramIsolatedRows() {
    List<PackageModel> packages = new java.util.ArrayList<>();
    for (String name : List.of("a", "b", "c", "d", "e", "f")) {
      packages.add(new PackageModel(ROOT + "." + name, List.of(), List.of()));
    }

    assertThat(
            DependencyDiagramGenerator.packages(model(List.of(), List.of(), packages))
                .orElseThrow()
                .code())
        .endsWith(
            """
                n1 ~~~ n2 ~~~ n3 ~~~ n4
                n5 ~~~ n6
              end
            """);
  }

  @Test
  @DisplayName(
      "Layer diagram counts components per role and labels edges with the dependency count")
  void layerDiagram() {
    Diagram diagram = DependencyDiagramGenerator.layers(billing()).orElseThrow();

    assertThat(diagram.code())
        .isEqualTo(
            """
            flowchart TB
              controller["Controller（1）"]
              service["Service（2）"]
              repository["Repository（1）"]
              controller -->|"依存 1 件"| service
              service -->|"依存 2 件"| service
              service -->|"依存 1 件"| repository
            """);
  }

  @Test
  @DisplayName("Per-package component diagrams split a large project for the export")
  void perPackageDiagrams() {
    List<Diagram> diagrams = DependencyDiagramGenerator.componentsPerPackage(billing());

    assertThat(diagrams)
        .extracting(Diagram::id, Diagram::title)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("components-1", "コンポーネント依存（repository）"),
            org.assertj.core.groups.Tuple.tuple("components-2", "コンポーネント依存（service.impl）"),
            org.assertj.core.groups.Tuple.tuple("components-3", "コンポーネント依存（web）"));
  }

  @Test
  @DisplayName("A project without components or packages has no diagrams")
  void emptyProject() {
    ProjectModel empty = model(List.of(), List.of(), List.of());

    assertThat(DependencyDiagramGenerator.components(empty, null).diagram()).isNull();
    assertThat(DependencyDiagramGenerator.packages(empty)).isEmpty();
    assertThat(DependencyDiagramGenerator.layers(empty)).isEmpty();
  }

  @Test
  @DisplayName("The default package is shown by name, since Mermaid rejects an empty label")
  void defaultPackageIsShownByName() {
    ProjectModel model = billing();

    assertThat(DependencyDiagramGenerator.relativePackage(model, "")).isEqualTo("デフォルトパッケージ");
    assertThat(DependencyDiagramGenerator.relativePackage(model, ROOT + ".web")).isEqualTo("web");
  }
}
