package com.example.javaanalyzer.ir;

import java.util.List;

/**
 * A link or form target written in a template (FR-17). Only targets written as a literal path are
 * linked to endpoints; nothing is evaluated.
 *
 * @param element tag name, e.g. {@code a}, {@code form}, {@code form:form}
 * @param attribute the attribute the value comes from, e.g. {@code th:href}, {@code action}
 * @param value the attribute value as written
 * @param path the application path written in {@code value}, e.g. {@code /owners/{ownerId}}; null
 *     when the value is an expression or a relative path
 * @param httpMethod GET for a link; for a form, its {@code method} in upper case, or the default of
 *     the tag when none is written (GET for {@code form}, POST for {@code form:form})
 * @param line 1-based line in the template
 * @param targets endpoints whose path and HTTP method match; empty when none does
 */
public record TemplateLinkModel(
    String element,
    String attribute,
    String value,
    String path,
    String httpMethod,
    int line,
    List<EndpointRef> targets) {

  public TemplateLinkModel {
    targets = List.copyOf(targets);
  }

  /** Returns a copy linked to the given endpoints. */
  public TemplateLinkModel withTargets(List<EndpointRef> newTargets) {
    return new TemplateLinkModel(element, attribute, value, path, httpMethod, line, newTargets);
  }
}
