package com.example.javaanalyzer.analysis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves type names written in one source file to fully qualified names, using only facts
 * available without a classpath: declared/nested types, single-type imports, the same package,
 * on-demand imports of project packages, and well-known {@code java.lang} types. Anything else
 * stays unresolved (null) instead of being guessed.
 *
 * <p>A resolver obtained with {@link #scopedTo} follows Java's scope rules for member types: they
 * are visible only inside the body of their enclosing types, so a type's own annotations and an
 * unrelated nested type do not see them (e.g. {@code @Data} on a class that declares a nested
 * {@code Data} still means {@code lombok.Data}). The file-wide resolver, used where no AST node is
 * at hand, treats every type declared in the file as visible.
 */
public final class TypeNameResolver {

  private static final Set<String> PRIMITIVES =
      Set.of("boolean", "byte", "char", "short", "int", "long", "float", "double", "void");

  private static final Set<String> JAVA_LANG_TYPES =
      Set.of(
          "Object",
          "String",
          "Boolean",
          "Byte",
          "Character",
          "Short",
          "Integer",
          "Long",
          "Float",
          "Double",
          "Number",
          "Void",
          "Enum",
          "Record",
          "Class",
          "Iterable",
          "Comparable",
          "CharSequence",
          "Runnable",
          "Thread",
          "Throwable",
          "Exception",
          "Error",
          "RuntimeException",
          "IllegalArgumentException",
          "IllegalStateException",
          "NullPointerException",
          "UnsupportedOperationException",
          "Math",
          "StringBuilder",
          "System",
          "Override",
          "Deprecated",
          "SuppressWarnings",
          "FunctionalInterface",
          "SafeVarargs",
          "AutoCloseable",
          "Cloneable");

  private final String packageName;
  private final Map<String, String> singleTypeImports;
  private final List<String> onDemandImports;
  private final List<String> typesDeclaredInFile;
  private final Set<String> projectTypes;

  /** Enclosing types whose body contains the reference, innermost first; null when file-wide. */
  private final List<String> scope;

  /**
   * @param typesDeclaredInFile qualified names of all (incl. nested) types declared in this file
   * @param projectTypes qualified names of all types in the project
   */
  public TypeNameResolver(
      CompilationUnit unit, List<String> typesDeclaredInFile, Set<String> projectTypes) {
    this.packageName = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
    Map<String, String> singleTypes = new HashMap<>();
    List<String> onDemand = new ArrayList<>();
    for (ImportDeclaration importDeclaration : unit.getImports()) {
      if (importDeclaration.isStatic()) {
        continue;
      }
      String name = importDeclaration.getNameAsString();
      if (importDeclaration.isAsterisk()) {
        onDemand.add(name);
      } else {
        singleTypes.put(name.substring(name.lastIndexOf('.') + 1), name);
      }
    }
    this.singleTypeImports = Map.copyOf(singleTypes);
    this.onDemandImports = List.copyOf(onDemand);
    this.typesDeclaredInFile = List.copyOf(typesDeclaredInFile);
    this.projectTypes = Set.copyOf(projectTypes);
    this.scope = null;
  }

  private TypeNameResolver(TypeNameResolver fileResolver, List<String> scope) {
    this.packageName = fileResolver.packageName;
    this.singleTypeImports = fileResolver.singleTypeImports;
    this.onDemandImports = fileResolver.onDemandImports;
    this.typesDeclaredInFile = fileResolver.typesDeclaredInFile;
    this.projectTypes = fileResolver.projectTypes;
    this.scope = List.copyOf(scope);
  }

  /**
   * A resolver for names written at the given node: member types are visible only when the node is
   * inside the body of their enclosing type (record components and enum constants count as body).
   */
  public TypeNameResolver scopedTo(Node node) {
    List<String> enclosing = new ArrayList<>();
    Node child = node;
    for (Optional<Node> parent = node.getParentNode();
        parent.isPresent();
        parent = parent.get().getParentNode()) {
      if (parent.get() instanceof TypeDeclaration<?> type && isInBody(type, child)) {
        type.getFullyQualifiedName().ifPresent(enclosing::add);
      }
      child = parent.get();
    }
    return new TypeNameResolver(this, enclosing);
  }

  private static boolean isInBody(TypeDeclaration<?> type, Node child) {
    return type.getMembers().stream().anyMatch(member -> member == child)
        || (type instanceof RecordDeclaration record
            && record.getParameters().stream().anyMatch(parameter -> parameter == child))
        || (type instanceof EnumDeclaration enumType
            && enumType.getEntries().stream().anyMatch(entry -> entry == child));
  }

  /**
   * Returns the fully qualified name for a type name as written (simple, nested like {@code
   * Map.Entry}, or already qualified), or null when it cannot be determined.
   */
  public String resolveName(String name) {
    if (PRIMITIVES.contains(name)) {
      return name;
    }
    int dot = name.indexOf('.');
    if (dot < 0) {
      return resolveSimpleName(name);
    }
    String head = name.substring(0, dot);
    String resolvedHead = resolveSimpleName(head);
    if (resolvedHead != null) {
      return resolvedHead + name.substring(dot);
    }
    return Character.isLowerCase(head.charAt(0)) ? name : null;
  }

  /**
   * Whether a name as written refers to the given qualified name. Unlike {@link #resolveName}, a
   * simple name matches when the target's package is imported on demand (e.g. {@code
   * jakarta.persistence.*}), since that is the only remaining place it can come from.
   */
  public boolean refersTo(String nameAsWritten, String qualifiedName) {
    String resolved = resolveName(nameAsWritten);
    if (resolved != null) {
      return resolved.equals(qualifiedName);
    }
    int lastDot = qualifiedName.lastIndexOf('.');
    return nameAsWritten.indexOf('.') < 0
        && lastDot > 0
        && qualifiedName.substring(lastDot + 1).equals(nameAsWritten)
        && onDemandImports.contains(qualifiedName.substring(0, lastDot));
  }

  private String resolveSimpleName(String simpleName) {
    String member = scope == null ? fileWideMemberType(simpleName) : memberTypeInScope(simpleName);
    if (member != null) {
      return member;
    }
    return resolveImportedOrPackageType(simpleName, singleTypeImports.get(simpleName));
  }

  private String fileWideMemberType(String simpleName) {
    for (String declared : typesDeclaredInFile) {
      if (declared.equals(simpleName) || declared.endsWith("." + simpleName)) {
        return declared;
      }
    }
    for (String declared : typesDeclaredInFile) {
      String nested = declared + "." + simpleName;
      if (projectTypes.contains(nested)) {
        return nested;
      }
    }
    return null;
  }

  private String memberTypeInScope(String simpleName) {
    for (String enclosing : scope) {
      String member = enclosing + "." + simpleName;
      if (typesDeclaredInFile.contains(member) || projectTypes.contains(member)) {
        return member;
      }
    }
    return null;
  }

  private String resolveImportedOrPackageType(String simpleName, String imported) {
    if (imported != null) {
      return imported;
    }
    String samePackage = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    if (projectTypes.contains(samePackage)) {
      return samePackage;
    }
    for (String onDemand : onDemandImports) {
      String candidate = onDemand + "." + simpleName;
      if (projectTypes.contains(candidate)) {
        return candidate;
      }
    }
    return JAVA_LANG_TYPES.contains(simpleName) ? "java.lang." + simpleName : null;
  }
}
