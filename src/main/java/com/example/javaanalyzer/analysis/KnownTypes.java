package com.example.javaanalyzer.analysis;

import com.example.javaanalyzer.ir.TypeRef;
import java.util.Optional;
import java.util.Set;

/**
 * Recognizes framework types a plugin knows by name. A known type imported on demand (e.g. {@code
 * import java.util.*;}) has no qualified name in the IR, so it is matched the same way annotations
 * are: only when the import names its package and no project type shadows it.
 */
public final class KnownTypes {

  private KnownTypes() {}

  /** The name in {@code knownNames} that the type refers to, if any. */
  public static Optional<String> match(
      TypeRef type, TypeNameResolver resolver, Set<String> knownNames) {
    if (type.resolved()) {
      return knownNames.contains(type.qualifiedName())
          ? Optional.of(type.qualifiedName())
          : Optional.empty();
    }
    String nameAsWritten = withoutTypeArguments(type.text());
    return knownNames.stream().filter(name -> resolver.refersTo(nameAsWritten, name)).findFirst();
  }

  private static String withoutTypeArguments(String text) {
    int typeArguments = text.indexOf('<');
    return typeArguments < 0 ? text : text.substring(0, typeArguments);
  }
}
