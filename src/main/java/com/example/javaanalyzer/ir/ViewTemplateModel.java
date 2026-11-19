package com.example.javaanalyzer.ir;

import java.util.List;

/**
 * A screen template (FR-17). Templates belong to no Java package.
 *
 * @param path relative to the project root with forward slashes
 * @param viewName the view name a controller returns to render this template; null when it cannot
 *     be determined from the settings (e.g. a JSP without {@code spring.mvc.view.prefix}) or the
 *     file is not a view (e.g. a {@code .jspf} fragment)
 * @param links links and form targets, in the order written
 * @param includes included parts, in the order written
 */
public record ViewTemplateModel(
    String path,
    TemplateKind kind,
    String viewName,
    List<TemplateLinkModel> links,
    List<TemplateIncludeModel> includes) {

  public ViewTemplateModel {
    links = List.copyOf(links);
    includes = List.copyOf(includes);
  }

  /** Returns a copy with the given links. */
  public ViewTemplateModel withLinks(List<TemplateLinkModel> newLinks) {
    return new ViewTemplateModel(path, kind, viewName, newLinks, includes);
  }
}
