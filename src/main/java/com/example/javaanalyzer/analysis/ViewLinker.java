package com.example.javaanalyzer.analysis;

import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.EndpointRef;
import com.example.javaanalyzer.ir.ViewReturnKind;
import com.example.javaanalyzer.ir.ViewReturnModel;
import com.example.javaanalyzer.ir.ViewTemplateModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Links the view layer (FR-17) using only facts already in the IR: a view name to the templates
 * declaring it, and a literal URL (a {@code redirect:}/{@code forward:} target or a template link)
 * to the endpoints mapped to that path. Nothing written as an expression is linked.
 *
 * <p>Paths are compared after removing the query and fragment, a trailing slash and the names of
 * path variables, so {@code /owners/{id}} matches {@code /owners/{ownerId}}. A literal segment
 * never matches a path variable, and patterns such as {@code *} are not interpreted. A forward
 * keeps the HTTP method of the original request, which is unknown, so it matches endpoints of any
 * method.
 */
final class ViewLinker {

  private static final String GET = "GET";
  private static final String FRAGMENT_SEPARATOR = "::";
  private static final Pattern PATH_VARIABLE = Pattern.compile("[{][^/]*[}]");

  private final List<EndpointModel> endpoints;
  private final List<ViewTemplateModel> views;
  private final Map<String, List<String>> templatePathsByViewName = new LinkedHashMap<>();

  ViewLinker(List<EndpointModel> endpoints, List<ViewTemplateModel> views) {
    this.endpoints = endpoints;
    this.views = views.stream().sorted(Comparator.comparing(ViewTemplateModel::path)).toList();
    for (ViewTemplateModel view : this.views) {
      if (view.viewName() != null) {
        templatePathsByViewName
            .computeIfAbsent(view.viewName(), name -> new ArrayList<>())
            .add(view.path());
      }
    }
  }

  List<EndpointModel> linkedEndpoints() {
    return endpoints.stream()
        .map(e -> e.withViewReturns(e.viewReturns().stream().map(this::link).toList()))
        .toList();
  }

  /** Templates sorted by path, with their links resolved. */
  List<ViewTemplateModel> linkedViews() {
    return views.stream()
        .map(
            view ->
                view.withLinks(
                    view.links().stream()
                        .map(l -> l.withTargets(targets(l.path(), l.httpMethod())))
                        .toList()))
        .toList();
  }

  private ViewReturnModel link(ViewReturnModel viewReturn) {
    if (!viewReturn.resolved() || viewReturn.kind() == null) {
      return viewReturn.withLinks(List.of(), List.of());
    }
    if (viewReturn.kind() == ViewReturnKind.VIEW) {
      List<String> templatePaths =
          templatePathsByViewName.getOrDefault(templateName(viewReturn.value()), List.of());
      return viewReturn.withLinks(templatePaths, List.of());
    }
    String httpMethod = viewReturn.kind() == ViewReturnKind.REDIRECT ? GET : null;
    return viewReturn.withLinks(List.of(), targets(viewReturn.value(), httpMethod));
  }

  /** A Thymeleaf view name may select a fragment: {@code "owners/list :: table"}. */
  private static String templateName(String viewName) {
    int separator = viewName.indexOf(FRAGMENT_SEPARATOR);
    return separator < 0 ? viewName : viewName.substring(0, separator).strip();
  }

  /**
   * @param httpMethod null to match any method
   */
  private List<EndpointRef> targets(String path, String httpMethod) {
    if (path == null || !path.startsWith("/")) {
      return List.of();
    }
    String normalized = normalize(path);
    return endpoints.stream()
        .filter(EndpointModel::pathsResolved)
        .filter(
            e ->
                httpMethod == null
                    || e.httpMethods().isEmpty()
                    || e.httpMethods().contains(httpMethod))
        .filter(e -> e.paths().stream().map(ViewLinker::normalize).anyMatch(normalized::equals))
        .map(e -> new EndpointRef(e.controllerType(), e.methodName()))
        .distinct()
        .toList();
  }

  static String normalize(String path) {
    String result = path;
    for (char end : new char[] {'#', '?'}) {
      int index = result.indexOf(end);
      if (index >= 0) {
        result = result.substring(0, index);
      }
    }
    result = PATH_VARIABLE.matcher(result).replaceAll("{}").replaceAll("/{2,}", "/");
    if (result.length() > 1 && result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
