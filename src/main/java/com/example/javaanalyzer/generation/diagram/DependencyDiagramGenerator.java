package com.example.javaanalyzer.generation.diagram;

import com.example.javaanalyzer.generation.DependencyTables;
import com.example.javaanalyzer.generation.Diagram;
import com.example.javaanalyzer.generation.Labels;
import com.example.javaanalyzer.ir.ComponentModel;
import com.example.javaanalyzer.ir.ComponentRole;
import com.example.javaanalyzer.ir.DependencyModel;
import com.example.javaanalyzer.ir.DependencyResolution;
import com.example.javaanalyzer.ir.PackageModel;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.TypeModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Dependency diagrams of SCR-03: layers (FR-05), packages (FR-12) and components (FR-08).
 *
 * <p>Edges are the dependencies resolved to a project type (screen-design 2.3): a solid line for a
 * direct or collection-element resolution, a dashed line when only resolved through the single
 * implementation of an interface. Nodes in a dependency cycle are highlighted; the cycles are found
 * here on the drawn graph, the same graph from which No.10 reports them as issues.
 */
public final class DependencyDiagramGenerator {

  /** Packages without dependencies are drawn this many per row, keeping the diagram narrow. */
  static final int ISOLATED_PER_ROW = 4;

  /** Above this, the component diagram requires a package filter (screen-design SCR-03). */
  public static final int MAX_UNFILTERED_NODES = 50;

  private static final String CYCLE_STYLE = "  classDef cycle stroke:#d32f2f,stroke-width:3px\n";

  private static final List<ComponentRole> ROLE_ORDER =
      List.of(
          ComponentRole.CONTROLLER,
          ComponentRole.SERVICE,
          ComponentRole.REPOSITORY,
          ComponentRole.CONFIGURATION,
          ComponentRole.COMPONENT);

  private DependencyDiagramGenerator() {}

  /**
   * @param packageName package whose components and their direct neighbors are drawn; null for all
   */
  public static ComponentDiagram components(ProjectModel model, String packageName) {
    Graph graph = componentGraph(model);
    int nodeCount = graph.nodes().size();
    if (nodeCount == 0) {
      return new ComponentDiagram(null, 0, false);
    }
    if (packageName == null) {
      if (nodeCount > MAX_UNFILTERED_NODES) {
        return new ComponentDiagram(null, nodeCount, true);
      }
      return new ComponentDiagram(
          componentDiagram(model, graph, graph.nodes(), "components", "コンポーネント依存"),
          nodeCount,
          false);
    }
    return new ComponentDiagram(
        componentDiagram(
            model,
            graph,
            neighborhood(model, graph, packageName),
            "components",
            "コンポーネント依存（" + relativePackage(model, packageName) + "）"),
        nodeCount,
        false);
  }

  /** One component diagram per package, for exporting a project too large for one page. */
  public static List<Diagram> componentsPerPackage(ProjectModel model) {
    Graph graph = componentGraph(model);
    Set<String> packages = new TreeSet<>();
    for (String node : graph.nodes()) {
      packages.add(packageOf(model, node));
    }
    List<Diagram> diagrams = new ArrayList<>();
    for (String packageName : packages) {
      diagrams.add(
          componentDiagram(
              model,
              graph,
              neighborhood(model, graph, packageName),
              "components-" + (diagrams.size() + 1),
              "コンポーネント依存（" + relativePackage(model, packageName) + "）"));
    }
    return diagrams;
  }

  /**
   * Package dependencies (FR-12), from the aggregation of No.10. Packages with no dependency in
   * either direction are grouped as "依存関係なし". Empty when there are no packages.
   */
  public static Optional<Diagram> packages(ProjectModel model) {
    if (model.packages().isEmpty()) {
      return Optional.empty();
    }
    Graph graph = new Graph();
    for (PackageModel packageModel : model.packages()) {
      graph.nodes().add(packageModel.name());
      for (String target : packageModel.dependsOn()) {
        graph.addEdge(packageModel.name(), target, false);
      }
    }
    Map<String, String> ids = ids(graph.nodes());
    Set<String> connected = new TreeSet<>();
    graph
        .edges()
        .forEach(
            (from, targets) -> {
              if (!targets.isEmpty()) {
                connected.add(from);
                connected.addAll(targets.keySet());
              }
            });
    StringBuilder code = new StringBuilder("flowchart LR\n");
    StringBuilder isolated = new StringBuilder();
    for (String node : graph.nodes()) {
      StringBuilder target = connected.contains(node) ? code : isolated.append("  ");
      target
          .append("  ")
          .append(ids.get(node))
          .append('[')
          .append(MermaidText.quoted(relativePackage(model, node)))
          .append("]\n");
    }
    if (!isolated.isEmpty()) {
      code.append("  subgraph isolated[\"依存関係なし\"]\n")
          .append("    direction LR\n")
          .append(isolated);
      // Invisible links put at most ISOLATED_PER_ROW packages side by side; rows stack
      List<String> isolatedIds =
          graph.nodes().stream().filter(node -> !connected.contains(node)).map(ids::get).toList();
      for (int start = 0; start < isolatedIds.size(); start += ISOLATED_PER_ROW) {
        List<String> row =
            isolatedIds.subList(start, Math.min(start + ISOLATED_PER_ROW, isolatedIds.size()));
        if (row.size() > 1) {
          code.append("    ").append(String.join(" ~~~ ", row)).append('\n');
        }
      }
      code.append("  end\n");
    }
    appendEdges(code, graph, ids, graph.nodes());
    appendCycles(code, graph, ids, graph.nodes());
    return Optional.of(new Diagram("packages", "パッケージ依存", code.toString()));
  }

  /**
   * Layers (FR-05): components per role and, on each edge, the number of component-to-component
   * dependencies between the two roles.
   */
  public static Optional<Diagram> layers(ProjectModel model) {
    Map<String, ComponentRole> roles = new HashMap<>();
    Map<ComponentRole, Integer> counts = new TreeMap<>();
    for (ComponentModel component : model.components()) {
      roles.put(component.typeName(), component.role());
      counts.merge(component.role(), 1, Integer::sum);
    }
    if (counts.isEmpty()) {
      return Optional.empty();
    }
    Map<ComponentRole, Map<ComponentRole, Integer>> edges = new TreeMap<>();
    Graph graph = componentGraph(model);
    graph
        .edges()
        .forEach(
            (from, targets) ->
                targets.forEach(
                    (to, dashed) -> {
                      ComponentRole fromRole = roles.get(from);
                      ComponentRole toRole = roles.get(to);
                      if (fromRole != null && toRole != null) {
                        edges
                            .computeIfAbsent(fromRole, unused -> new TreeMap<>())
                            .merge(toRole, 1, Integer::sum);
                      }
                    }));
    StringBuilder code = new StringBuilder("flowchart TB\n");
    for (ComponentRole role : ROLE_ORDER) {
      if (counts.containsKey(role)) {
        code.append("  ")
            .append(layerId(role))
            .append('[')
            .append(MermaidText.quoted(Labels.role(role) + "（" + counts.get(role) + "）"))
            .append("]\n");
      }
    }
    for (ComponentRole from : ROLE_ORDER) {
      Map<ComponentRole, Integer> targets = edges.getOrDefault(from, Map.of());
      for (ComponentRole to : ROLE_ORDER) {
        Integer count = targets.get(to);
        if (count != null) {
          code.append("  ")
              .append(layerId(from))
              .append(" -->|\"依存 ")
              .append(count)
              .append(" 件\"| ")
              .append(layerId(to))
              .append('\n');
        }
      }
    }
    return Optional.of(new Diagram("layers", "レイヤー構成", code.toString()));
  }

  /** The package of a type, preferring the IR's record over the name (nested types). */
  static String packageOf(ProjectModel model, String typeName) {
    for (TypeModel type : model.types()) {
      if (type.qualifiedName().equals(typeName)) {
        return type.packageName();
      }
    }
    return Labels.packageOf(typeName);
  }

  /** A package name without the root package prefix, which every package shares. */
  static String relativePackage(ProjectModel model, String packageName) {
    String root = model.rootPackage();
    if (root != null && !root.isEmpty() && packageName.startsWith(root + ".")) {
      return packageName.substring(root.length() + 1);
    }
    return Labels.packageName(packageName);
  }

  private static Graph componentGraph(ProjectModel model) {
    Graph graph = new Graph();
    for (ComponentModel component : model.components()) {
      graph.nodes().add(component.typeName());
    }
    for (DependencyModel dependency : model.dependencies()) {
      if (DependencyTables.isDrawn(dependency)) {
        graph.addEdge(
            dependency.fromType(),
            dependency.resolvedType(),
            dependency.resolution() == DependencyResolution.SINGLE_IMPLEMENTATION);
      }
    }
    return graph;
  }

  private static Set<String> neighborhood(ProjectModel model, Graph graph, String packageName) {
    Set<String> nodes = new TreeSet<>();
    for (String node : graph.nodes()) {
      if (packageOf(model, node).equals(packageName)) {
        nodes.add(node);
      }
    }
    Set<String> inPackage = Set.copyOf(nodes);
    graph
        .edges()
        .forEach(
            (from, targets) ->
                targets
                    .keySet()
                    .forEach(
                        to -> {
                          if (inPackage.contains(from) || inPackage.contains(to)) {
                            nodes.add(from);
                            nodes.add(to);
                          }
                        }));
    return nodes;
  }

  private static Diagram componentDiagram(
      ProjectModel model, Graph graph, Set<String> shown, String id, String title) {
    Map<String, String> ids = ids(shown);
    Map<String, List<String>> byPackage = new TreeMap<>();
    for (String node : shown) {
      byPackage.computeIfAbsent(packageOf(model, node), unused -> new ArrayList<>()).add(node);
    }
    StringBuilder code = new StringBuilder("flowchart LR\n");
    int packageIndex = 1;
    for (Map.Entry<String, List<String>> entry : byPackage.entrySet()) {
      code.append("  subgraph p")
          .append(packageIndex++)
          .append('[')
          .append(MermaidText.quoted(relativePackage(model, entry.getKey())))
          .append("]\n");
      for (String node : entry.getValue()) {
        code.append("    ")
            .append(ids.get(node))
            .append('[')
            .append(MermaidText.quoted(Labels.simpleName(node)))
            .append("]\n");
      }
      code.append("  end\n");
    }
    appendEdges(code, graph, ids, shown);
    appendCycles(code, graph, ids, shown);
    return new Diagram(id, title, code.toString());
  }

  /** Node ids n1, n2, ... in the sorted order of the names. */
  private static Map<String, String> ids(Set<String> nodes) {
    Map<String, String> ids = new HashMap<>();
    for (String node : new TreeSet<>(nodes)) {
      ids.put(node, "n" + (ids.size() + 1));
    }
    return ids;
  }

  private static void appendEdges(
      StringBuilder code, Graph graph, Map<String, String> ids, Set<String> shown) {
    List<int[]> lines = new ArrayList<>();
    Map<String, Boolean> dashedByKey = new HashMap<>();
    graph
        .edges()
        .forEach(
            (from, targets) ->
                targets.forEach(
                    (to, dashed) -> {
                      if (shown.contains(from) && shown.contains(to)) {
                        int[] pair = {number(ids.get(from)), number(ids.get(to))};
                        lines.add(pair);
                        dashedByKey.put(pair[0] + ">" + pair[1], dashed);
                      }
                    }));
    lines.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
    for (int[] line : lines) {
      boolean dashed = dashedByKey.get(line[0] + ">" + line[1]);
      code.append("  n")
          .append(line[0])
          .append(dashed ? " -.-> n" : " --> n")
          .append(line[1])
          .append('\n');
    }
  }

  private static void appendCycles(
      StringBuilder code, Graph graph, Map<String, String> ids, Set<String> shown) {
    List<Integer> cycleIds =
        graph.cycleNodes().stream()
            .filter(shown::contains)
            .map(node -> number(ids.get(node)))
            .sorted()
            .toList();
    if (cycleIds.isEmpty()) {
      return;
    }
    code.append(CYCLE_STYLE).append("  class ");
    code.append(String.join(",", cycleIds.stream().map(n -> "n" + n).toList()));
    code.append(" cycle\n");
  }

  private static int number(String id) {
    return Integer.parseInt(id.substring(1));
  }

  private static String layerId(ComponentRole role) {
    return role.name().toLowerCase(java.util.Locale.ROOT);
  }
}
