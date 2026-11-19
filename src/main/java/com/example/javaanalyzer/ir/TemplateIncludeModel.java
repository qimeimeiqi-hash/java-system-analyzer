package com.example.javaanalyzer.ir;

import java.util.List;

/**
 * A part included into a template (FR-17), e.g. {@code th:replace}, {@code layout:decorate}, {@code
 * <jsp:include>} or {@code <%@ include %>}.
 *
 * @param attribute the attribute or directive, e.g. {@code th:replace}, {@code jsp:include}
 * @param value the value as written
 * @param templateName the Thymeleaf template name or the JSP path written in {@code value}; null
 *     when it is an expression
 * @param templatePaths the included templates, relative to the project root; empty when not found
 * @param line 1-based line in the template
 */
public record TemplateIncludeModel(
    String attribute, String value, String templateName, List<String> templatePaths, int line) {

  public TemplateIncludeModel {
    templatePaths = List.copyOf(templatePaths);
  }
}
