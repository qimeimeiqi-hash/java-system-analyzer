package com.example.javaanalyzer.analysis.plugin.springboot;

import com.example.javaanalyzer.analysis.AnalysisContext;
import com.example.javaanalyzer.analysis.AnnotationSupport;
import com.example.javaanalyzer.analysis.KnownTypes;
import com.example.javaanalyzer.analysis.ParsedType;
import com.example.javaanalyzer.analysis.StructureExtractor;
import com.example.javaanalyzer.analysis.TypeNameResolver;
import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.ParameterModel;
import com.example.javaanalyzer.ir.TypeRef;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Extracts HTTP endpoints (FR-06) from Spring MVC request mappings of controllers.
 *
 * <p>The view names a method returns (FR-17) are extracted by {@link ViewReturnExtractor} unless
 * the return value is the response body.
 *
 * <p>Known limitations: mappings inherited from interfaces or superclasses, and paths given by
 * constants (kept as raw text, {@code pathsResolved=false}) are not resolved.
 */
class RequestMappingAnalyzer {

  /** Shortcut mapping annotation to its HTTP method. */
  private static final Map<String, String> SHORTCUT_MAPPINGS =
      Map.of(
          AnnotationNames.WEB_BIND + "GetMapping", "GET",
          AnnotationNames.WEB_BIND + "PostMapping", "POST",
          AnnotationNames.WEB_BIND + "PutMapping", "PUT",
          AnnotationNames.WEB_BIND + "DeleteMapping", "DELETE",
          AnnotationNames.WEB_BIND + "PatchMapping", "PATCH");

  /** Wrappers whose single type argument is the actual response body. */
  private static final Set<String> RESPONSE_WRAPPERS =
      Set.of("org.springframework.http.ResponseEntity", "org.springframework.http.HttpEntity");

  private record MappedPaths(List<String> paths, boolean resolved) {}

  void analyze(AnalysisContext context) {
    for (ParsedType parsed : context.parsedTypes()) {
      TypeNameResolver resolver = parsed.resolver();
      if (!AnnotationSupport.has(
          parsed.declaration(),
          resolver,
          AnnotationNames.CONTROLLER,
          AnnotationNames.REST_CONTROLLER)) {
        continue;
      }
      boolean bodyController =
          AnnotationSupport.has(
              parsed.declaration(),
              resolver,
              AnnotationNames.REST_CONTROLLER,
              AnnotationNames.RESPONSE_BODY);
      Optional<AnnotationExpr> classMapping =
          AnnotationSupport.find(parsed.declaration(), resolver, AnnotationNames.REQUEST_MAPPING);
      MappedPaths prefixes =
          classMapping
              .map(RequestMappingAnalyzer::paths)
              .orElse(new MappedPaths(List.of(""), true));
      List<String> classMethods =
          classMapping.map(RequestMappingAnalyzer::methodAttribute).orElse(List.of());
      for (MethodDeclaration method : parsed.declaration().getMethods()) {
        mappingOf(method, resolver)
            .ifPresent(
                mapping ->
                    context.addEndpoint(
                        endpoint(parsed, method, mapping, prefixes, classMethods, bodyController)));
      }
    }
  }

  private static Optional<AnnotationExpr> mappingOf(
      MethodDeclaration method, TypeNameResolver resolver) {
    return method.getAnnotations().stream()
        .filter(
            a ->
                AnnotationSupport.refersToAny(a, resolver, AnnotationNames.REQUEST_MAPPING)
                    || AnnotationSupport.refersToAny(
                        a, resolver, SHORTCUT_MAPPINGS.keySet().toArray(String[]::new)))
        .findFirst();
  }

  private static EndpointModel endpoint(
      ParsedType parsed,
      MethodDeclaration method,
      AnnotationExpr mapping,
      MappedPaths prefixes,
      List<String> classMethods,
      boolean bodyController) {
    TypeNameResolver resolver = parsed.resolver();
    MappedPaths methodPaths = paths(mapping);
    boolean resolved = prefixes.resolved() && methodPaths.resolved();
    List<String> fullPaths = new ArrayList<>();
    for (String prefix : prefixes.paths()) {
      for (String path : methodPaths.paths()) {
        fullPaths.add(joinPaths(prefix, path, resolved));
      }
    }

    TypeRef requestBodyType = null;
    for (Parameter parameter : method.getParameters()) {
      if (AnnotationSupport.has(parameter, resolver, AnnotationNames.REQUEST_BODY)) {
        requestBodyType = StructureExtractor.typeRef(parameter.getType(), resolver);
      }
    }
    TypeRef returnType = StructureExtractor.typeRef(method.getType(), resolver);
    List<ParameterModel> parameters =
        method.getParameters().stream()
            .map(
                p ->
                    new ParameterModel(
                        p.getNameAsString(),
                        StructureExtractor.typeRef(p.getType(), resolver),
                        StructureExtractor.annotations(p.getAnnotations(), resolver)))
            .toList();

    TypeRef responseType = unwrapResponse(returnType, resolver);
    boolean responseBody =
        bodyController
            || AnnotationSupport.has(method, resolver, AnnotationNames.RESPONSE_BODY)
            || KnownTypes.match(returnType, resolver, RESPONSE_WRAPPERS).isPresent();
    return new EndpointModel(
        parsed.qualifiedName(),
        method.getNameAsString(),
        combined(classMethods, httpMethods(mapping, resolver)),
        fullPaths,
        resolved,
        requestBodyType,
        returnType,
        responseType,
        parameters,
        responseBody,
        responseBody
            ? List.of()
            : ViewReturnExtractor.extract(parsed.declaration(), method, resolver));
  }

  /** Spring combines type- and method-level request methods as a union, type-level first. */
  private static List<String> combined(List<String> classMethods, List<String> methodMethods) {
    Set<String> union = new LinkedHashSet<>(classMethods);
    union.addAll(methodMethods);
    return List.copyOf(union);
  }

  private static List<String> httpMethods(AnnotationExpr mapping, TypeNameResolver resolver) {
    for (Map.Entry<String, String> shortcut : SHORTCUT_MAPPINGS.entrySet()) {
      if (AnnotationSupport.refersToAny(mapping, resolver, shortcut.getKey())) {
        return List.of(shortcut.getValue());
      }
    }
    return methodAttribute(mapping);
  }

  private static List<String> methodAttribute(AnnotationExpr mapping) {
    return AnnotationSupport.attribute(mapping, "method")
        .map(
            e -> AnnotationSupport.values(e).stream().map(AnnotationSupport::constantName).toList())
        .orElse(List.of());
  }

  /** Paths from {@code value} or {@code path}; a mapping without paths maps to "". */
  private static MappedPaths paths(AnnotationExpr mapping) {
    Optional<Expression> expression =
        AnnotationSupport.attribute(mapping, "value")
            .or(() -> AnnotationSupport.attribute(mapping, "path"));
    if (expression.isEmpty()) {
      return new MappedPaths(List.of(""), true);
    }
    List<String> paths = new ArrayList<>();
    boolean resolved = true;
    for (Expression value : AnnotationSupport.values(expression.get())) {
      Optional<String> literal = AnnotationSupport.stringValue(value);
      resolved &= literal.isPresent();
      paths.add(literal.orElse(value.toString()));
    }
    return new MappedPaths(paths.isEmpty() ? List.of("") : paths, resolved);
  }

  /**
   * Joins like Spring does: one slash between segments and a leading slash. The leading slash is
   * not added when a segment is an unresolved expression, whose value may already start with one.
   */
  static String joinPaths(String prefix, String path, boolean resolved) {
    String joined = prefix.isEmpty() || path.isEmpty() ? prefix + path : prefix + "/" + path;
    joined = joined.replaceAll("/{2,}", "/");
    return resolved && !joined.startsWith("/") ? "/" + joined : joined;
  }

  private static TypeRef unwrapResponse(TypeRef returnType, TypeNameResolver resolver) {
    if (KnownTypes.match(returnType, resolver, RESPONSE_WRAPPERS).isPresent()
        && returnType.typeArguments().size() == 1) {
      return returnType.typeArguments().get(0);
    }
    return returnType;
  }
}
