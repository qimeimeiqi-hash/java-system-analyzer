package com.example.javaanalyzer.web;

import com.example.javaanalyzer.generation.ApiListGenerator;
import com.example.javaanalyzer.generation.ClassListGenerator;
import com.example.javaanalyzer.generation.ClassOverviewGenerator;
import com.example.javaanalyzer.generation.ConfigurationReportGenerator;
import com.example.javaanalyzer.generation.DependencyTables;
import com.example.javaanalyzer.generation.EntityTables;
import com.example.javaanalyzer.generation.IssueReportGenerator;
import com.example.javaanalyzer.generation.Labels;
import com.example.javaanalyzer.generation.MyBatisReportGenerator;
import com.example.javaanalyzer.generation.Table;
import com.example.javaanalyzer.generation.ViewReportGenerator;
import com.example.javaanalyzer.generation.diagram.DependencyDiagramGenerator;
import com.example.javaanalyzer.generation.diagram.ErDiagramGenerator;
import com.example.javaanalyzer.generation.diagram.ViewTransitionDiagramGenerator;
import com.example.javaanalyzer.ir.ComponentModel;
import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.EntityKind;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.IssueKind;
import com.example.javaanalyzer.ir.IssueSeverity;
import com.example.javaanalyzer.ir.MapperModel;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.TypeModel;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Result screens SCR-03 to SCR-10 (FR-10). Every screen is built by the generation layer from the
 * saved IR. A project that is not analyzed (unsupported, failed or outdated IR) is sent back to
 * SCR-02, which explains why.
 */
@Controller
public class ResultPagesController {

  private final ProjectAnalysisService analysisService;

  public ResultPagesController(ProjectAnalysisService analysisService) {
    this.analysisService = analysisService;
  }

  /** SCR-03: layers (FR-05), packages (FR-12) and components (FR-08). */
  @GetMapping("/projects/{projectId}/architecture")
  public String architecture(
      @PathVariable String projectId,
      @RequestParam(required = false) String tab,
      @RequestParam(name = "package", required = false) String packageName,
      Model model,
      HttpServletResponse response) {
    return page(
        projectId,
        model,
        response,
        "architecture",
        ir -> {
          String selectedTab = "layers".equals(tab) || "components".equals(tab) ? tab : "packages";
          String selectedPackage = blankToNull(packageName);
          model.addAttribute("tab", selectedTab);
          model.addAttribute("selectedPackage", selectedPackage);
          model.addAttribute("packageChoices", componentPackages(ir));
          model.addAttribute("maxNodes", DependencyDiagramGenerator.MAX_UNFILTERED_NODES);
          switch (selectedTab) {
            case "layers" ->
                model.addAttribute("diagram", DependencyDiagramGenerator.layers(ir).orElse(null));
            case "components" -> {
              var components = DependencyDiagramGenerator.components(ir, selectedPackage);
              model.addAttribute("diagram", components.diagram());
              model.addAttribute("componentDiagram", components);
            }
            default ->
                model.addAttribute("diagram", DependencyDiagramGenerator.packages(ir).orElse(null));
          }
          model.addAttribute("unresolved", DependencyTables.unresolved(ir));
        });
  }

  /** SCR-04: the API list (FR-06). */
  @GetMapping("/projects/{projectId}/apis")
  public String apis(
      @PathVariable String projectId,
      @RequestParam(required = false) String controller,
      Model model,
      HttpServletResponse response) {
    return page(
        projectId,
        model,
        response,
        "apis",
        ir -> {
          String selected = blankToNull(controller);
          model.addAttribute("selectedController", selected);
          model.addAttribute(
              "controllerChoices",
              choices(ir.endpoints().stream().map(EndpointModel::controllerType).toList()));
          model.addAttribute("apiTable", ApiListGenerator.table(ir, selected));
        });
  }

  /** SCR-05: the ER diagram and entity tables (FR-07). */
  @GetMapping("/projects/{projectId}/er")
  public String er(@PathVariable String projectId, Model model, HttpServletResponse response) {
    return page(
        projectId,
        model,
        response,
        "er",
        ir -> {
          model.addAttribute("diagram", ErDiagramGenerator.diagram(ir.entities()).orElse(null));
          model.addAttribute("entityList", EntityTables.entityList(ir.entities()));
          model.addAttribute("entities", entitySections(ir));
        });
  }

  /** SCR-06 list. */
  @GetMapping("/projects/{projectId}/classes")
  public String classes(
      @PathVariable String projectId,
      @RequestParam(name = "package", required = false) String packageName,
      @RequestParam(required = false) String role,
      Model model,
      HttpServletResponse response) {
    return page(
        projectId,
        model,
        response,
        "classes",
        ir -> {
          String selectedPackage = blankToNull(packageName);
          String selectedRole = blankToNull(role);
          model.addAttribute("selectedPackage", selectedPackage);
          model.addAttribute("selectedRole", selectedRole);
          model.addAttribute("packageChoices", ClassListGenerator.packages(ir));
          model.addAttribute("roleChoices", ClassListGenerator.roles(ir));
          model.addAttribute(
              "classTable", ClassListGenerator.table(ir, selectedPackage, selectedRole));
        });
  }

  /** SCR-06 detail: the FR-09 template of one class and its simple call graph (FR-18). */
  @GetMapping("/projects/{projectId}/classes/{qualifiedName:.+}")
  public String classDetail(
      @PathVariable String projectId,
      @PathVariable String qualifiedName,
      Model model,
      HttpServletResponse response) {
    Optional<ProjectResult> loaded = analysisService.load(projectId);
    if (loaded.isEmpty()) {
      response.setStatus(HttpStatus.NOT_FOUND.value());
      return ProjectController.NOT_FOUND_VIEW;
    }
    if (!loaded.get().analyzed()) {
      return "redirect:/projects/" + projectId;
    }
    var overview = ClassOverviewGenerator.of(loaded.get().model(), qualifiedName);
    if (overview.isEmpty()) {
      response.setStatus(HttpStatus.NOT_FOUND.value());
      return ProjectController.NOT_FOUND_VIEW;
    }
    addCommon(model, loaded.get(), "classes");
    model.addAttribute("overview", overview.get());
    return "project/class-detail";
  }

  /** SCR-07: the analysis issue report (FR-14). */
  @GetMapping("/projects/{projectId}/issues")
  public String issues(
      @PathVariable String projectId,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String severity,
      Model model,
      HttpServletResponse response) {
    return page(
        projectId,
        model,
        response,
        "issues",
        ir -> {
          IssueKind selectedKind = parse(IssueKind.class, kind);
          IssueSeverity selectedSeverity = parse(IssueSeverity.class, severity);
          model.addAttribute("selectedKind", selectedKind);
          model.addAttribute("selectedSeverity", selectedSeverity);
          Map<String, String> kinds = new LinkedHashMap<>();
          for (IssueKind value : IssueKind.values()) {
            kinds.put(value.name(), Labels.issueKind(value));
          }
          Map<String, String> severities = new LinkedHashMap<>();
          for (IssueSeverity value : IssueSeverity.values()) {
            severities.put(value.name(), Labels.severity(value));
          }
          model.addAttribute("kindChoices", kinds);
          model.addAttribute("severityChoices", severities);
          model.addAttribute(
              "issueTable", IssueReportGenerator.table(ir, selectedKind, selectedSeverity));
          model.addAttribute("note", IssueReportGenerator.NOTE);
        });
  }

  /** SCR-08: MyBatis mappers and SQL (FR-15). */
  @GetMapping("/projects/{projectId}/mybatis")
  public String mybatis(
      @PathVariable String projectId,
      @RequestParam(required = false) String mapper,
      Model model,
      HttpServletResponse response) {
    return page(
        projectId,
        model,
        response,
        "mybatis",
        ir -> {
          String selected = blankToNull(mapper);
          model.addAttribute("selectedMapper", selected);
          model.addAttribute(
              "mapperChoices",
              choices(
                  ir.mappers().stream()
                      .map(MapperModel::interfaceType)
                      .filter(name -> name != null)
                      .toList()));
          model.addAttribute(
              "mappers",
              MyBatisReportGenerator.mappers(ir).stream()
                  .filter(m -> selected == null || selected.equals(m.interfaceType()))
                  .toList());
          model.addAttribute(
              "orphans",
              selected == null ? MyBatisReportGenerator.namespacesWithoutInterface(ir) : List.of());
          model.addAttribute("note", MyBatisReportGenerator.INCLUDE_NOTE);
        });
  }

  /** SCR-09: @Bean, @ConfigurationProperties and @Configuration classes (FR-16). */
  @GetMapping("/projects/{projectId}/configuration")
  public String configuration(
      @PathVariable String projectId, Model model, HttpServletResponse response) {
    return page(
        projectId,
        model,
        response,
        "configuration",
        ir -> {
          model.addAttribute("beanTable", ConfigurationReportGenerator.beans(ir));
          model.addAttribute("propertiesTable", ConfigurationReportGenerator.properties(ir));
          model.addAttribute(
              "configurationTable", ConfigurationReportGenerator.configurationClasses(ir));
        });
  }

  /** SCR-10: screens and screen transitions (FR-17). */
  @GetMapping("/projects/{projectId}/views")
  public String views(@PathVariable String projectId, Model model, HttpServletResponse response) {
    return page(
        projectId,
        model,
        response,
        "views",
        ir -> {
          model.addAttribute(
              "templateDirectoryNote", ViewReportGenerator.templateDirectoryNote(ir).orElse(null));
          model.addAttribute("screenTable", ViewReportGenerator.screens(ir));
          model.addAttribute("diagram", ViewTransitionDiagramGenerator.diagram(ir).orElse(null));
          model.addAttribute("transitionTable", ViewReportGenerator.transitions(ir));
          model.addAttribute("unmatchedTable", ViewReportGenerator.unmatched(ir));
        });
  }

  /** One section per entity type: its columns and relations. */
  private static List<EntityView> entitySections(ProjectModel ir) {
    return ir.entities().stream()
        .sorted(Comparator.comparing(EntityModel::typeName))
        .map(
            entity ->
                new EntityView(
                    entity.typeName(),
                    Labels.simpleName(entity.typeName())
                        + "（"
                        + Labels.entityKind(entity.kind())
                        + (entity.kind() == EntityKind.ENTITY
                            ? "：テーブル " + Labels.orUnspecified(entity.tableName())
                            : "")
                        + "）",
                    EntityTables.columns(entity),
                    EntityTables.relations(entity)))
        .toList();
  }

  /** Entity section of SCR-05. */
  public record EntityView(String typeName, String heading, Table columns, Table relations) {}

  private String page(
      String projectId,
      Model model,
      HttpServletResponse response,
      String active,
      Consumer<ProjectModel> fill) {
    Optional<ProjectResult> loaded = analysisService.load(projectId);
    if (loaded.isEmpty()) {
      response.setStatus(HttpStatus.NOT_FOUND.value());
      return ProjectController.NOT_FOUND_VIEW;
    }
    if (!loaded.get().analyzed()) {
      return "redirect:/projects/" + projectId;
    }
    addCommon(model, loaded.get(), active);
    fill.accept(loaded.get().model());
    return "project/" + active;
  }

  private static void addCommon(Model model, ProjectResult project, String active) {
    model.addAttribute("project", project);
    model.addAttribute("active", active);
    model.addAttribute(
        "typeNames",
        project.model().types().stream()
            .map(TypeModel::qualifiedName)
            .collect(Collectors.toUnmodifiableSet()));
  }

  /** Qualified name to simple name, sorted by qualified name, for a select box. */
  private static Map<String, String> choices(List<String> qualifiedNames) {
    Map<String, String> choices = new LinkedHashMap<>();
    new TreeSet<>(qualifiedNames).forEach(name -> choices.put(name, Labels.simpleName(name)));
    return choices;
  }

  private static List<String> componentPackages(ProjectModel ir) {
    Set<String> packages = new TreeSet<>();
    Map<String, String> typePackages =
        ir.types().stream()
            .collect(
                Collectors.toMap(TypeModel::qualifiedName, TypeModel::packageName, (a, b) -> a));
    for (ComponentModel component : ir.components()) {
      packages.add(
          typePackages.getOrDefault(component.typeName(), Labels.packageOf(component.typeName())));
    }
    return List.copyOf(packages);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
