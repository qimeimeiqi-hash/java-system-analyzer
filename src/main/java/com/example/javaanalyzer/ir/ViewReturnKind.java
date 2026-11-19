package com.example.javaanalyzer.ir;

/** What a controller method's view name means to Spring MVC (FR-17). */
public enum ViewReturnKind {
  /** A view name rendered by a template. */
  VIEW,
  /** {@code redirect:} followed by a URL. */
  REDIRECT,
  /** {@code forward:} followed by a URL. */
  FORWARD
}
