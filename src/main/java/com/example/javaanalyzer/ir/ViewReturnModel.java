package com.example.javaanalyzer.ir;

import java.util.List;

/**
 * A view name written in a controller method (FR-17): a returned {@code String}, the view name of a
 * {@code ModelAndView}, or a {@code redirect:}/{@code forward:} URL.
 *
 * @param kind null when the value is an expression whose meaning is not known
 * @param value the view name, or the URL without its {@code redirect:}/{@code forward:} prefix; the
 *     expression as written when not resolved
 * @param resolved false when {@code value} holds an expression instead of a known string
 * @param line 1-based source line
 * @param templatePaths templates rendering this view name (VIEW only), relative to the project
 *     root; empty when none is found. More than one when both JSP and Thymeleaf define the name
 * @param targets endpoints whose path matches the URL (REDIRECT/FORWARD only)
 */
public record ViewReturnModel(
    ViewReturnKind kind,
    String value,
    boolean resolved,
    int line,
    List<String> templatePaths,
    List<EndpointRef> targets) {

  public ViewReturnModel {
    templatePaths = List.copyOf(templatePaths);
    targets = List.copyOf(targets);
  }

  /** Returns a copy linked to the given templates and endpoints. */
  public ViewReturnModel withLinks(List<String> newTemplatePaths, List<EndpointRef> newTargets) {
    return new ViewReturnModel(kind, value, resolved, line, newTemplatePaths, newTargets);
  }
}
