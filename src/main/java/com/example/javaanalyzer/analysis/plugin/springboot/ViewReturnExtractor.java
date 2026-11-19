package com.example.javaanalyzer.analysis.plugin.springboot;

import com.example.javaanalyzer.analysis.StructureExtractor;
import com.example.javaanalyzer.analysis.TypeNameResolver;
import com.example.javaanalyzer.ir.ViewReturnKind;
import com.example.javaanalyzer.ir.ViewReturnModel;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Extracts the view names a controller method writes (FR-17): the values it returns when its return
 * type is {@code String}, and the view names given to a {@code ModelAndView} (constructor and
 * {@code setViewName}). A value is known only when written as a string literal, a concatenation of
 * literals, or a {@code static final} constant of the same class initialized that way; anything
 * else is kept as written ({@code resolved=false}).
 *
 * <p>Known limitations: constants of other classes and view names of {@code View} objects are not
 * resolved; {@code setViewName} is recognized by name alone; a method returning {@code null} or
 * nothing gets the default view name from the request path at run time, which depends on settings
 * and is not inferred; views registered with {@code WebMvcConfigurer#addViewControllers} are not
 * extracted.
 */
final class ViewReturnExtractor {

  private static final String REDIRECT_PREFIX = "redirect:";
  private static final String FORWARD_PREFIX = "forward:";
  private static final Set<String> STRING_TYPES = Set.of("java.lang.String");
  private static final String MODEL_AND_VIEW = "org.springframework.web.servlet.ModelAndView";
  private static final String SET_VIEW_NAME = "setViewName";

  /**
   * A view name expression: its string value when known, or else the known beginning of it (e.g.
   * {@code "redirect:/owners/"} of {@code "redirect:/owners/" + id}).
   */
  private record Value(Expression expression, String text, String knownPrefix) {}

  private final TypeDeclaration<?> controller;
  private final MethodDeclaration method;
  private final Set<String> localNames;

  private ViewReturnExtractor(TypeDeclaration<?> controller, MethodDeclaration method) {
    this.controller = controller;
    this.method = method;
    this.localNames =
        method.findAll(VariableDeclarator.class).stream()
            .map(VariableDeclarator::getNameAsString)
            .collect(Collectors.toSet());
    method.findAll(Parameter.class).forEach(p -> localNames.add(p.getNameAsString()));
  }

  static List<ViewReturnModel> extract(
      TypeDeclaration<?> controller, MethodDeclaration method, TypeNameResolver resolver) {
    return new ViewReturnExtractor(controller, method).extract(resolver);
  }

  private List<ViewReturnModel> extract(TypeNameResolver resolver) {
    String returnType = StructureExtractor.typeRef(method.getType(), resolver).qualifiedName();
    List<Expression> expressions = new ArrayList<>();
    if (STRING_TYPES.contains(returnType)) {
      method.findAll(ReturnStmt.class).stream()
          .filter(this::belongsToMethod)
          .forEach(r -> r.getExpression().ifPresent(expressions::add));
    } else if (MODEL_AND_VIEW.equals(returnType)) {
      method.findAll(ObjectCreationExpr.class).stream()
          .filter(this::belongsToMethod)
          .filter(c -> !c.getArguments().isEmpty())
          .filter(
              c ->
                  MODEL_AND_VIEW.equals(
                      StructureExtractor.typeRef(c.getType(), resolver).qualifiedName()))
          .forEach(c -> expressions.add(c.getArgument(0)));
      method.findAll(MethodCallExpr.class).stream()
          .filter(this::belongsToMethod)
          .filter(c -> c.getNameAsString().equals(SET_VIEW_NAME) && c.getArguments().size() == 1)
          .forEach(c -> expressions.add(c.getArgument(0)));
      expressions.sort(Comparator.comparing(e -> e.getBegin().orElseThrow()));
    }
    List<ViewReturnModel> result = new ArrayList<>();
    for (Expression expression : expressions) {
      for (Value value : values(expression)) {
        result.add(model(value));
      }
    }
    return result;
  }

  /** False for code of a lambda or a class declared inside the method. */
  private boolean belongsToMethod(Node node) {
    for (Node current = node.getParentNode().orElse(null);
        current != null && current != method;
        current = current.getParentNode().orElse(null)) {
      if (current instanceof LambdaExpr || current instanceof TypeDeclaration<?>) {
        return false;
      }
      if (current instanceof ObjectCreationExpr creation
          && creation.getAnonymousClassBody().isPresent()
          && creation != node) {
        return false;
      }
    }
    return true;
  }

  /** Each value the expression can have; both branches of a conditional, none for null. */
  private List<Value> values(Expression expression) {
    if (expression instanceof EnclosedExpr enclosed) {
      return values(enclosed.getInner());
    }
    if (expression instanceof NullLiteralExpr) {
      return List.of();
    }
    if (expression instanceof ConditionalExpr conditional) {
      List<Value> values = new ArrayList<>(values(conditional.getThenExpr()));
      values.addAll(values(conditional.getElseExpr()));
      return values;
    }
    Optional<String> text = constantText(expression);
    return List.of(
        new Value(
            expression, text.orElse(null), text.isPresent() ? null : knownPrefix(expression)));
  }

  private static ViewReturnModel model(Value value) {
    int line = value.expression().getBegin().map(p -> p.line).orElse(0);
    boolean resolved = value.text() != null;
    String known = resolved ? value.text() : value.knownPrefix();
    ViewReturnKind kind = null;
    String target = resolved ? value.text() : value.expression().toString();
    if (known != null && known.startsWith(REDIRECT_PREFIX)) {
      kind = ViewReturnKind.REDIRECT;
      target = resolved ? known.substring(REDIRECT_PREFIX.length()) : target;
    } else if (known != null && known.startsWith(FORWARD_PREFIX)) {
      kind = ViewReturnKind.FORWARD;
      target = resolved ? known.substring(FORWARD_PREFIX.length()) : target;
    } else if (resolved) {
      kind = ViewReturnKind.VIEW;
    }
    return new ViewReturnModel(kind, target, resolved, line, List.of(), List.of());
  }

  /** The string value of a literal, a concatenation of known values or a known constant. */
  private Optional<String> constantText(Expression expression) {
    if (expression instanceof StringLiteralExpr literal) {
      return Optional.of(literal.asString());
    }
    if (expression instanceof TextBlockLiteralExpr textBlock) {
      return Optional.of(textBlock.asString());
    }
    if (expression instanceof EnclosedExpr enclosed) {
      return constantText(enclosed.getInner());
    }
    if (expression instanceof BinaryExpr binary
        && binary.getOperator() == BinaryExpr.Operator.PLUS) {
      Optional<String> left = constantText(binary.getLeft());
      Optional<String> right = constantText(binary.getRight());
      return left.isPresent() && right.isPresent()
          ? Optional.of(left.get() + right.get())
          : Optional.empty();
    }
    if (expression instanceof NameExpr name && !localNames.contains(name.getNameAsString())) {
      return constantValue(name.getNameAsString());
    }
    if (expression instanceof FieldAccessExpr access && isOwnClass(access.getScope())) {
      return constantValue(access.getNameAsString());
    }
    return Optional.empty();
  }

  /** The known beginning of a concatenation whose value is not fully known. */
  private String knownPrefix(Expression expression) {
    Expression current = expression;
    while (current instanceof BinaryExpr binary
        && binary.getOperator() == BinaryExpr.Operator.PLUS) {
      current = binary.getLeft();
    }
    return current == expression ? null : constantText(current).orElse(null);
  }

  private boolean isOwnClass(Expression scope) {
    return scope instanceof ThisExpr
        || scope instanceof NameExpr name
            && name.getNameAsString().equals(controller.getNameAsString());
  }

  /** A {@code static final} field of the controller initialized with a known string. */
  private Optional<String> constantValue(String fieldName) {
    for (FieldDeclaration field : controller.getFields()) {
      if (!field.isStatic() || !field.isFinal()) {
        continue;
      }
      for (VariableDeclarator variable : field.getVariables()) {
        if (variable.getNameAsString().equals(fieldName)) {
          return variable.getInitializer().flatMap(ViewReturnExtractor::literalText);
        }
      }
    }
    return Optional.empty();
  }

  /** Literals only, so that constants referring to each other cannot loop. */
  private static Optional<String> literalText(Expression expression) {
    if (expression instanceof StringLiteralExpr literal) {
      return Optional.of(literal.asString());
    }
    if (expression instanceof BinaryExpr binary
        && binary.getOperator() == BinaryExpr.Operator.PLUS) {
      Optional<String> left = literalText(binary.getLeft());
      Optional<String> right = literalText(binary.getRight());
      return left.isPresent() && right.isPresent()
          ? Optional.of(left.get() + right.get())
          : Optional.empty();
    }
    return Optional.empty();
  }
}
