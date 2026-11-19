package com.example.javaanalyzer.generation;

import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.ProjectModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the API list (FR-06, SCR-04) with exactly six items: controller, method, HTTP method,
 * path, request DTO and response DTO. A method mapped to several paths gives one row per path.
 */
public final class ApiListGenerator {

  public static final List<String> HEADERS =
      List.of("Controller", "Method", "HTTP", "Path", "Request DTO", "Response DTO");

  static final String ALL_METHODS = "全メソッド";

  private ApiListGenerator() {}

  /**
   * @param controllerType qualified name of the only controller to list; null lists all
   */
  public static Table table(ProjectModel model, String controllerType) {
    List<Row> rows = new ArrayList<>();
    for (EndpointModel endpoint : model.endpoints()) {
      if (controllerType != null && !controllerType.equals(endpoint.controllerType())) {
        continue;
      }
      List<String> paths = endpoint.paths().isEmpty() ? List.of(Labels.DASH) : endpoint.paths();
      for (String path : paths) {
        String shownPath =
            endpoint.pathsResolved() || path.equals(Labels.DASH)
                ? path
                : path + Labels.UNRESOLVED_SUFFIX;
        rows.add(new Row(endpoint, path, shownPath));
      }
    }
    rows.sort(
        Comparator.comparing((Row row) -> row.endpoint().controllerType())
            .thenComparing(Row::path)
            .thenComparing(row -> row.endpoint().methodName()));
    return new Table(HEADERS, rows.stream().map(ApiListGenerator::cells).toList());
  }

  private static List<Cell> cells(Row row) {
    EndpointModel endpoint = row.endpoint();
    return List.of(
        Cell.typeName(endpoint.controllerType()),
        Cell.of(endpoint.methodName()),
        Cell.of(
            endpoint.httpMethods().isEmpty()
                ? ALL_METHODS
                : String.join(", ", endpoint.httpMethods())),
        Cell.of(row.shownPath()),
        Cell.type(endpoint.requestBodyType()),
        Cell.type(endpoint.responseType()));
  }

  private record Row(EndpointModel endpoint, String path, String shownPath) {}
}
