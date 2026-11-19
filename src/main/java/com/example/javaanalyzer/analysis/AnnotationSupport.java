package com.example.javaanalyzer.analysis;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Helpers for reading annotations from the AST without symbol resolution. */
public final class AnnotationSupport {

  private AnnotationSupport() {}

  /** Finds the first annotation on the node that refers to one of the given qualified names. */
  public static Optional<AnnotationExpr> find(
      NodeWithAnnotations<?> node, TypeNameResolver resolver, String... qualifiedNames) {
    return node.getAnnotations().stream()
        .filter(annotation -> refersToAny(annotation, resolver, qualifiedNames))
        .findFirst();
  }

  public static boolean has(
      NodeWithAnnotations<?> node, TypeNameResolver resolver, String... qualifiedNames) {
    return find(node, resolver, qualifiedNames).isPresent();
  }

  public static boolean refersToAny(
      AnnotationExpr annotation, TypeNameResolver resolver, String... qualifiedNames) {
    String name = annotation.getNameAsString();
    TypeNameResolver scoped = resolver.scopedTo(annotation);
    return Arrays.stream(qualifiedNames).anyMatch(qn -> scoped.refersTo(name, qn));
  }

  /**
   * Returns an attribute expression. {@code value} also matches the single-member form
   * ({@code @Table("x")}).
   */
  public static Optional<Expression> attribute(AnnotationExpr annotation, String name) {
    if (annotation instanceof SingleMemberAnnotationExpr single) {
      return "value".equals(name) ? Optional.of(single.getMemberValue()) : Optional.empty();
    }
    if (annotation instanceof NormalAnnotationExpr normal) {
      return normal.getPairs().stream()
          .filter(pair -> pair.getNameAsString().equals(name))
          .map(MemberValuePair::getValue)
          .findFirst();
    }
    return Optional.empty();
  }

  /** Elements of an array initializer, or the expression itself as a single element. */
  public static List<Expression> values(Expression expression) {
    if (expression instanceof ArrayInitializerExpr array) {
      return List.copyOf(array.getValues());
    }
    return List.of(expression);
  }

  /** A string literal, or a concatenation of string literals; empty for anything else. */
  public static Optional<String> stringValue(Expression expression) {
    if (expression instanceof StringLiteralExpr literal) {
      return Optional.of(literal.asString());
    }
    if (expression instanceof BinaryExpr binary
        && binary.getOperator() == BinaryExpr.Operator.PLUS) {
      Optional<String> left = stringValue(binary.getLeft());
      Optional<String> right = stringValue(binary.getRight());
      if (left.isPresent() && right.isPresent()) {
        return Optional.of(left.get() + right.get());
      }
    }
    return Optional.empty();
  }

  public static Optional<Boolean> booleanValue(Expression expression) {
    return expression instanceof BooleanLiteralExpr literal
        ? Optional.of(literal.getValue())
        : Optional.empty();
  }

  public static Optional<Integer> intValue(Expression expression) {
    return expression instanceof IntegerLiteralExpr literal
        ? Optional.of(literal.asNumber().intValue())
        : Optional.empty();
  }

  /** Last segment of an enum constant reference: {@code CascadeType.ALL} becomes {@code ALL}. */
  public static String constantName(Expression expression) {
    String text = expression.toString();
    return text.substring(text.lastIndexOf('.') + 1);
  }
}
