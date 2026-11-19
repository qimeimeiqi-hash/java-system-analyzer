package com.example.javaanalyzer.ir;

/** How the target of one injection was determined from the declared type (FR-08). */
public enum DependencyResolution {
  /** The declared type itself is the target: a project class, repository or lone interface. */
  PROJECT_TYPE,
  /**
   * The declared interface or abstract class has exactly one implementing component in the sources.
   * A {@code @Bean} method or profile could still provide another one at run time.
   */
  SINGLE_IMPLEMENTATION,
  /** One of the beans injected together, e.g. through {@code List<Foo>}; one entry per bean. */
  COLLECTION_ELEMENT,
  /**
   * Several implementing components exist; choosing one would be a guess, so there is no target.
   */
  AMBIGUOUS,
  /** The type is outside the project, e.g. a library class, and no project type extends it. */
  EXTERNAL,
  /** The declared type could not be determined, or its source could not be parsed. */
  UNRESOLVED
}
