package com.example.javaanalyzer.generation;

import com.example.javaanalyzer.generation.ScreenTransitions.Transition;
import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.EndpointRef;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.TemplateIncludeModel;
import com.example.javaanalyzer.ir.TemplateLinkModel;
import com.example.javaanalyzer.ir.ViewReturnKind;
import com.example.javaanalyzer.ir.ViewReturnModel;
import com.example.javaanalyzer.ir.ViewTemplateModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tables of the screen list (FR-17, SCR-10). Expressions are never evaluated: a link or view name
 * written as an expression is shown as written and marked unresolved.
 *
 * <p>Template paths are shown relative to the directory common to all templates, which the outputs
 * state once above the tables ({@link #templateDirectoryNote}).
 */
public final class ViewReportGenerator {

  private ViewReportGenerator() {}

  /**
   * The longest directory, ending with "/", that contains every template; empty when there are no
   * templates or no shared directory. With one template, its own directory.
   */
  public static String templateDirectory(ProjectModel model) {
    List<String> paths = model.views().stream().map(ViewTemplateModel::path).toList();
    if (paths.isEmpty()) {
      return "";
    }
    String common = paths.get(0).substring(0, paths.get(0).lastIndexOf('/') + 1);
    for (String path : paths) {
      while (!path.startsWith(common)) {
        common = common.substring(0, common.lastIndexOf('/', common.length() - 2) + 1);
      }
    }
    return common;
  }

  /** "テンプレートの場所：…" for the outputs; empty when there is no common directory. */
  public static Optional<String> templateDirectoryNote(ProjectModel model) {
    String directory = templateDirectory(model);
    return directory.isEmpty() ? Optional.empty() : Optional.of("テンプレートの場所：" + directory);
  }

  public static Table screens(ProjectModel model) {
    String directory = templateDirectory(model);
    List<List<Cell>> rows = new ArrayList<>();
    for (ViewTemplateModel view : ScreenTransitions.sortedViews(model)) {
      List<EndpointModel> shownBy =
          model.endpoints().stream()
              .filter(
                  endpoint ->
                      endpoint.viewReturns().stream()
                          .anyMatch(
                              r ->
                                  r.kind() == ViewReturnKind.VIEW
                                      && r.templatePaths().contains(view.path())))
              .sorted(
                  Comparator.comparing(EndpointModel::controllerType)
                      .thenComparing(EndpointModel::methodName))
              .toList();
      Set<String> controllers = new TreeSet<>();
      shownBy.forEach(endpoint -> controllers.add(endpoint.controllerType()));
      rows.add(
          List.of(
              Cell.of(relative(directory, view.path())),
              Cell.of(view.kind().name()),
              Cell.of(Labels.orUnspecified(view.viewName())),
              new Cell(
                  lines(shownBy.stream().map(ViewReportGenerator::methodName).toList()),
                  controllers.size() == 1 ? controllers.iterator().next() : null),
              Cell.of(
                  lines(
                      view.includes().stream()
                          .map(include -> include(directory, include))
                          .toList()))));
    }
    return new Table(
        List.of("テンプレート", "種類", "View 名", "表示する Controller メソッド", "部品（include）"), rows);
  }

  public static Table transitions(ProjectModel model) {
    String directory = templateDirectory(model);
    List<List<Cell>> rows = new ArrayList<>();
    for (Transition transition : ScreenTransitions.of(model)) {
      TemplateLinkModel link = transition.link();
      String description =
          (link.httpMethod() == null ? "" : link.httpMethod() + " ")
              + link.value()
              + (link.path() == null ? Labels.UNRESOLVED_SUFFIX : "");
      rows.add(
          List.of(
              Cell.of(relative(directory, transition.from().path())),
              Cell.of(link.element() + "（" + link.attribute() + "）"),
              Cell.of(description),
              Cell.of(lines(link.targets().stream().map(ViewReportGenerator::refName).toList())),
              Cell.of(
                  lines(
                      transition.nextScreens().stream()
                          .map(path -> relative(directory, path))
                          .toList()))));
    }
    return new Table(List.of("画面", "要素", "記述", "遷移先 Controller メソッド", "遷移後の画面"), rows);
  }

  /** View names returned by controllers that could not be matched to a template. */
  public static Table unmatched(ProjectModel model) {
    List<List<Cell>> rows = new ArrayList<>();
    List<EndpointModel> endpoints =
        model.endpoints().stream()
            .sorted(
                Comparator.comparing(EndpointModel::controllerType)
                    .thenComparing(EndpointModel::methodName))
            .toList();
    for (EndpointModel endpoint : endpoints) {
      for (ViewReturnModel viewReturn : endpoint.viewReturns()) {
        String reason;
        if (!viewReturn.resolved()) {
          reason = "式のため未解決";
        } else if (viewReturn.kind() == ViewReturnKind.VIEW
            && viewReturn.templatePaths().isEmpty()) {
          reason = "テンプレートが見つからない";
        } else {
          continue;
        }
        rows.add(
            List.of(
                new Cell(methodName(endpoint), endpoint.controllerType()),
                Cell.of(kind(viewReturn.kind())),
                Cell.of(viewReturn.value()),
                Cell.of(reason)));
      }
    }
    return new Table(List.of("Controller メソッド", "種類", "記述", "理由"), rows);
  }

  private static String include(String directory, TemplateIncludeModel include) {
    return include.templatePaths().isEmpty()
        ? include.value() + Labels.UNRESOLVED_SUFFIX
        : String.join(
            "\n", include.templatePaths().stream().map(path -> relative(directory, path)).toList());
  }

  /**
   * One value per line, so that long method lists and paths wrap between values on every output.
   */
  private static String lines(List<String> values) {
    return values.isEmpty() ? Labels.DASH : String.join("\n", values);
  }

  private static String relative(String directory, String path) {
    return path.startsWith(directory) ? path.substring(directory.length()) : path;
  }

  private static String methodName(EndpointModel endpoint) {
    return Labels.simpleName(endpoint.controllerType()) + "." + endpoint.methodName();
  }

  private static String refName(EndpointRef ref) {
    return Labels.simpleName(ref.controllerType()) + "." + ref.methodName();
  }

  private static String kind(ViewReturnKind kind) {
    if (kind == null) {
      return "不明";
    }
    return switch (kind) {
      case VIEW -> "View";
      case REDIRECT -> "redirect";
      case FORWARD -> "forward";
    };
  }
}
