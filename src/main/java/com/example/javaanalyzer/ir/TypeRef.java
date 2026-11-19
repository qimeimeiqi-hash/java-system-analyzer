package com.example.javaanalyzer.ir;

import java.util.List;

/**
 * A type as written in source, plus its resolved name.
 *
 * @param text the type exactly as written, e.g. {@code List<Order>}
 * @param qualifiedName fully qualified name of the raw type, primitive name for primitives, or null
 *     when it cannot be determined from imports / project sources (never guessed)
 * @param typeArguments generic arguments in declaration order
 */
public record TypeRef(String text, String qualifiedName, List<TypeRef> typeArguments) {

  public TypeRef {
    typeArguments = List.copyOf(typeArguments);
  }

  public boolean resolved() {
    return qualifiedName != null;
  }
}
