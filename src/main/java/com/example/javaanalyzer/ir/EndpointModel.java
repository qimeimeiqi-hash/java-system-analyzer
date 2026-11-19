package com.example.javaanalyzer.ir;

import java.util.List;

/**
 * An HTTP endpoint (FR-06).
 *
 * @param httpMethods e.g. GET, POST; empty when the mapping accepts any method
 * @param paths full paths (type-level prefix joined with method-level path)
 * @param pathsResolved false when a path is not a string literal (e.g. a constant); such a path is
 *     kept as its raw expression text
 * @param requestBodyType type of the request body parameter, or null
 * @param returnType declared return type
 * @param responseType return type with a {@code ResponseEntity<T>} style wrapper removed
 * @param responseBody true when the return value is the response body ({@code @RestController},
 *     {@code @ResponseBody}) rather than a view
 * @param viewReturns view names written in the method (FR-17); empty for a response body
 */
public record EndpointModel(
    String controllerType,
    String methodName,
    List<String> httpMethods,
    List<String> paths,
    boolean pathsResolved,
    TypeRef requestBodyType,
    TypeRef returnType,
    TypeRef responseType,
    List<ParameterModel> parameters,
    boolean responseBody,
    List<ViewReturnModel> viewReturns) {

  public EndpointModel {
    httpMethods = List.copyOf(httpMethods);
    paths = List.copyOf(paths);
    parameters = List.copyOf(parameters);
    viewReturns = List.copyOf(viewReturns);
  }

  /** Returns a copy with the given parameters. */
  public EndpointModel withParameters(List<ParameterModel> newParameters) {
    return new EndpointModel(
        controllerType,
        methodName,
        httpMethods,
        paths,
        pathsResolved,
        requestBodyType,
        returnType,
        responseType,
        newParameters,
        responseBody,
        viewReturns);
  }

  /** Returns a copy with the given view returns. */
  public EndpointModel withViewReturns(List<ViewReturnModel> newViewReturns) {
    return new EndpointModel(
        controllerType,
        methodName,
        httpMethods,
        paths,
        pathsResolved,
        requestBodyType,
        returnType,
        responseType,
        parameters,
        responseBody,
        newViewReturns);
  }
}
