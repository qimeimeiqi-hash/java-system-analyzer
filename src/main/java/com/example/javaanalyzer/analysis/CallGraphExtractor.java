package com.example.javaanalyzer.analysis;

import com.example.javaanalyzer.ir.CallModel;
import com.example.javaanalyzer.ir.ComponentModel;
import com.example.javaanalyzer.ir.DependencyModel;
import com.example.javaanalyzer.ir.DependencyResolution;
import com.example.javaanalyzer.ir.InjectionKind;
import com.example.javaanalyzer.ir.InjectionModel;
import com.example.javaanalyzer.ir.ProjectModel;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Heuristic call graph (FR-18): method calls and method references on fields that received an
 * injection, found by matching the AST inside method bodies. No symbol resolution is used, so
 * overloads and polymorphism are not resolved; the target is the already resolved {@link
 * DependencyModel} of the field. Independent of any framework: it reads injections from the IR.
 *
 * <p>Known limitations: calls in constructors, in initializers and from nested classes are not
 * detected; calls on inherited fields and through getters are not detected; calls on wrappers such
 * as {@code List<Foo>} act on the wrapper and are left out, and so are calls on library types. The
 * IR does not say which method declares a setter or constructor injection, so its parameter is
 * found by name and type in declaration order; a method declared earlier with the same parameter
 * name and type, and not itself an injection point, would be taken instead.
 */
final class CallGraphExtractor {

  private record InjectedField(InjectionModel injection, DependencyModel dependency) {}

  private CallGraphExtractor() {}

  /** Returns the model with its calls, following the package order of its components. */
  static ProjectModel addCalls(ProjectModel model, List<ParsedType> parsedTypes) {
    Map<String, ParsedType> parsedByName =
        parsedTypes.stream()
            .collect(
                Collectors.toMap(
                    ParsedType::qualifiedName, Function.identity(), (first, second) -> first));
    List<CallModel> calls = new ArrayList<>();
    for (ComponentModel component : model.components()) {
      ParsedType parsed = parsedByName.get(component.typeName());
      if (parsed != null) {
        calls.addAll(callsOf(component, parsed, model.dependencies()));
      }
    }
    return model.withCalls(calls);
  }

  private static List<CallModel> callsOf(
      ComponentModel component, ParsedType parsed, List<DependencyModel> dependencies) {
    Map<String, InjectedField> fields = injectedFields(component, parsed, dependencies);
    List<CallModel> calls = new ArrayList<>();
    if (fields.isEmpty()) {
      return calls;
    }
    for (MethodDeclaration method : parsed.declaration().getMethods()) {
      method
          .getBody()
          .ifPresent(
              body -> {
                for (Expression expression : body.findAll(Expression.class)) {
                  callOf(expression, method, parsed.qualifiedName(), fields).ifPresent(calls::add);
                }
              });
    }
    return calls;
  }

  /** Field name to injection, leaving out wrappers and library types. */
  private static Map<String, InjectedField> injectedFields(
      ComponentModel component, ParsedType parsed, List<DependencyModel> dependencies) {
    List<DependencyModel> unmatched = new ArrayList<>();
    for (DependencyModel dependency : dependencies) {
      if (dependency.fromType().equals(component.typeName())) {
        unmatched.add(dependency);
      }
    }
    List<Parameter> usedParameters = new ArrayList<>();
    Map<String, InjectedField> fields = new LinkedHashMap<>();
    for (InjectionModel injection : component.injections()) {
      Optional<DependencyModel> dependency = takeDependency(unmatched, injection);
      Optional<Parameter> parameter =
          injection.kind() == InjectionKind.FIELD
              ? Optional.empty()
              : takeParameter(injection, parsed.declaration(), usedParameters);
      boolean wrapped = !injection.beanType().equals(injection.type());
      if (wrapped
          || dependency.isEmpty()
          || dependency.get().resolution() == DependencyResolution.EXTERNAL) {
        continue;
      }
      fieldNameOf(injection, parameter, parsed.declaration())
          .ifPresent(name -> fields.put(name, new InjectedField(injection, dependency.get())));
    }
    return fields;
  }

  /**
   * The first dependency recorded for this injection point. Dependencies are matched by kind, name
   * and declared type, since several setter or constructor parameters can share a name.
   */
  private static Optional<DependencyModel> takeDependency(
      List<DependencyModel> unmatched, InjectionModel injection) {
    for (DependencyModel dependency : unmatched) {
      if (dependency.injectionKind() == injection.kind()
          && dependency.memberName().equals(injection.memberName())
          && dependency.declaredType().equals(injection.type())) {
        unmatched.remove(dependency);
        return Optional.of(dependency);
      }
    }
    return Optional.empty();
  }

  /**
   * The source parameter of a constructor or setter injection: the first one, in declaration order,
   * with the injection's name and type that no earlier injection has taken. Empty for a constructor
   * generated by Lombok or a record's implicit constructor.
   */
  private static Optional<Parameter> takeParameter(
      InjectionModel injection, TypeDeclaration<?> type, List<Parameter> usedParameters) {
    List<? extends CallableDeclaration<?>> callables =
        injection.kind() == InjectionKind.CONSTRUCTOR ? type.getConstructors() : type.getMethods();
    for (CallableDeclaration<?> callable : callables) {
      Optional<Parameter> parameter =
          callable
              .getParameterByName(injection.memberName())
              .filter(p -> p.getType().asString().equals(injection.type().text()))
              .filter(p -> usedParameters.stream().noneMatch(used -> used == p));
      if (parameter.isPresent()) {
        usedParameters.add(parameter.get());
        return parameter;
      }
    }
    return Optional.empty();
  }

  /**
   * The field receiving an injection: the assignment from the parameter when written in source,
   * otherwise the field of the same name (always the case for a Lombok-generated constructor).
   */
  private static Optional<String> fieldNameOf(
      InjectionModel injection, Optional<Parameter> parameter, TypeDeclaration<?> type) {
    String member = injection.memberName();
    if (injection.kind() == InjectionKind.FIELD) {
      return Optional.of(member);
    }
    Optional<Node> callable = parameter.flatMap(Node::getParentNode);
    if (callable.isPresent()) {
      for (AssignExpr assignment : callable.get().findAll(AssignExpr.class)) {
        if (assignment.getValue() instanceof NameExpr value
            && value.getNameAsString().equals(member)) {
          Optional<String> target = assignedFieldName(assignment.getTarget(), type, member);
          if (target.isPresent()) {
            return target;
          }
        }
      }
    }
    return type.getFieldByName(member).map(field -> member);
  }

  private static Optional<String> assignedFieldName(
      Expression target, TypeDeclaration<?> type, String parameterName) {
    if (target instanceof FieldAccessExpr access && access.getScope() instanceof ThisExpr) {
      return Optional.of(access.getNameAsString());
    }
    if (target instanceof NameExpr name
        && !name.getNameAsString().equals(parameterName)
        && type.getFieldByName(name.getNameAsString()).isPresent()) {
      return Optional.of(name.getNameAsString());
    }
    return Optional.empty();
  }

  private static Optional<CallModel> callOf(
      Expression expression,
      MethodDeclaration method,
      String fromType,
      Map<String, InjectedField> fields) {
    if (expression instanceof MethodCallExpr call && call.getScope().isPresent()) {
      Optional<String> field = referencedField(call.getScope().get(), method, fields);
      int line = call.getName().getBegin().map(position -> position.line).orElse(0);
      return field.map(
          name ->
              callModel(
                  fromType,
                  method,
                  name,
                  fields.get(name),
                  call.getNameAsString(),
                  false,
                  call.getArguments().size(),
                  line));
    }
    if (expression instanceof MethodReferenceExpr reference) {
      Optional<String> field = referencedField(reference.getScope(), method, fields);
      int line = reference.getBegin().map(position -> position.line).orElse(0);
      return field.map(
          name ->
              callModel(
                  fromType,
                  method,
                  name,
                  fields.get(name),
                  reference.getIdentifier(),
                  true,
                  null,
                  line));
    }
    return Optional.empty();
  }

  private static CallModel callModel(
      String fromType,
      MethodDeclaration method,
      String fieldName,
      InjectedField field,
      String methodName,
      boolean methodReference,
      Integer argumentCount,
      int line) {
    return new CallModel(
        fromType,
        method.getNameAsString(),
        method.getParameters().stream().map(p -> p.getType().toString()).toList(),
        fieldName,
        methodName,
        methodReference,
        argumentCount,
        field.injection().type(),
        field.dependency().resolvedType(),
        field.dependency().resolution(),
        line);
  }

  /** The injected field a scope refers to, unless a local declaration shadows it. */
  private static Optional<String> referencedField(
      Expression scope, MethodDeclaration method, Map<String, InjectedField> fields) {
    if (scope instanceof FieldAccessExpr access
        && access.getScope() instanceof ThisExpr thisExpression
        && thisExpression.getTypeName().isEmpty()) {
      return Optional.of(access.getNameAsString()).filter(fields::containsKey);
    }
    return simpleName(scope)
        .filter(fields::containsKey)
        .filter(name -> !isShadowed(name, scope, method));
  }

  /** The parser reads the {@code repository} of {@code repository::save} as a type. */
  private static Optional<String> simpleName(Expression scope) {
    if (scope instanceof NameExpr name) {
      return Optional.of(name.getNameAsString());
    }
    if (scope instanceof TypeExpr typeExpression
        && typeExpression.getType() instanceof ClassOrInterfaceType type
        && type.getScope().isEmpty()
        && type.getTypeArguments().isEmpty()) {
      return Optional.of(type.getNameAsString());
    }
    return Optional.empty();
  }

  private static boolean isShadowed(String name, Node usage, MethodDeclaration method) {
    return method.findAll(Parameter.class).stream()
            .filter(parameter -> parameter.getNameAsString().equals(name))
            .anyMatch(
                parameter -> parameter.getParentNode().filter(p -> encloses(p, usage)).isPresent())
        || method.findAll(VariableDeclarator.class).stream()
            .filter(variable -> variable.getNameAsString().equals(name))
            .anyMatch(variable -> isBefore(variable, usage) && encloses(scopeOf(variable), usage))
        || method.findAll(TypePatternExpr.class).stream()
            .filter(pattern -> pattern.getNameAsString().equals(name))
            .anyMatch(pattern -> encloses(enclosingStatement(pattern), usage));
  }

  private static boolean isBefore(Node first, Node second) {
    return first.getBegin().isPresent()
        && second.getBegin().isPresent()
        && first.getBegin().get().isBefore(second.getBegin().get());
  }

  private static Node scopeOf(VariableDeclarator variable) {
    Node node = variable;
    while (node.getParentNode().isPresent()) {
      node = node.getParentNode().get();
      if (node instanceof BlockStmt
          || node instanceof ForStmt
          || node instanceof ForEachStmt
          || node instanceof TryStmt
          || node instanceof SwitchEntry
          || node instanceof ObjectCreationExpr) {
        return node;
      }
    }
    return node;
  }

  private static Node enclosingStatement(Node node) {
    Node current = node;
    while (!(current instanceof Statement) && current.getParentNode().isPresent()) {
      current = current.getParentNode().get();
    }
    return current;
  }

  private static boolean encloses(Node ancestor, Node node) {
    Node current = node;
    while (current != null) {
      if (current == ancestor) {
        return true;
      }
      current = current.getParentNode().orElse(null);
    }
    return false;
  }
}
