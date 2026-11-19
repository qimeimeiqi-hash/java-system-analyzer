package com.example.javaanalyzer.analysis;

import com.example.javaanalyzer.ir.AnnotationModel;
import com.example.javaanalyzer.ir.FieldModel;
import com.example.javaanalyzer.ir.MemberOrigin;
import com.example.javaanalyzer.ir.MethodModel;
import com.example.javaanalyzer.ir.ParameterModel;
import com.example.javaanalyzer.ir.TypeKind;
import com.example.javaanalyzer.ir.TypeModel;
import com.example.javaanalyzer.ir.TypeRef;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.WildcardType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Extracts the framework-independent structure of one type into the IR. */
public final class StructureExtractor {

  private StructureExtractor() {}

  /** Top-level and nested (not local) type declarations of a compilation unit. */
  public static List<TypeDeclaration<?>> declaredTypes(CompilationUnit unit) {
    List<TypeDeclaration<?>> result = new ArrayList<>();
    for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
      if ((type.isTopLevelType() || type.isNestedType())
          && type.getFullyQualifiedName().isPresent()) {
        result.add(type);
      }
    }
    return result;
  }

  public static TypeModel extract(ParsedType parsed, CompilationUnit unit) {
    TypeDeclaration<?> type = parsed.declaration();
    TypeNameResolver resolver = parsed.resolver();
    TypeRef superClass = null;
    List<TypeRef> interfaces = new ArrayList<>();
    List<FieldModel> fields = new ArrayList<>();

    if (type instanceof ClassOrInterfaceDeclaration declaration) {
      if (declaration.isInterface()) {
        declaration.getExtendedTypes().forEach(t -> interfaces.add(typeRef(t, resolver)));
      } else {
        superClass =
            declaration.getExtendedTypes().getFirst().map(t -> typeRef(t, resolver)).orElse(null);
      }
      declaration.getImplementedTypes().forEach(t -> interfaces.add(typeRef(t, resolver)));
    } else if (type instanceof EnumDeclaration declaration) {
      declaration.getImplementedTypes().forEach(t -> interfaces.add(typeRef(t, resolver)));
    } else if (type instanceof RecordDeclaration declaration) {
      declaration.getImplementedTypes().forEach(t -> interfaces.add(typeRef(t, resolver)));
      for (Parameter component : declaration.getParameters()) {
        fields.add(
            new FieldModel(
                component.getNameAsString(),
                typeRef(component.getType(), resolver),
                List.of("private", "final"),
                annotations(component.getAnnotations(), resolver),
                null,
                null,
                false));
      }
    }

    for (FieldDeclaration field : type.getFields()) {
      for (VariableDeclarator variable : field.getVariables()) {
        fields.add(
            new FieldModel(
                variable.getNameAsString(),
                typeRef(variable.getType(), resolver),
                modifiers(field),
                annotations(field.getAnnotations(), resolver),
                javadoc(field),
                comment(field),
                variable.getInitializer().isPresent()));
      }
    }

    List<MethodModel> constructors = new ArrayList<>();
    type.getConstructors().forEach(c -> constructors.add(callable(c, null, resolver)));
    List<MethodModel> methods = new ArrayList<>();
    for (MethodDeclaration method : type.getMethods()) {
      methods.add(callable(method, typeRef(method.getType(), resolver), resolver));
    }

    String packageName = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
    return new TypeModel(
        parsed.qualifiedName(),
        type.getNameAsString(),
        packageName,
        kind(type),
        parsed.sourceFile(),
        modifiers(type),
        annotations(type.getAnnotations(), resolver),
        javadoc(type),
        comment(type),
        superClass,
        interfaces,
        unit.getImports().stream().map(StructureExtractor::importText).toList(),
        fields,
        constructors,
        methods);
  }

  public static TypeRef typeRef(Type type, TypeNameResolver resolver) {
    String text = type.toString();
    if (type.isPrimitiveType() || type.isVoidType()) {
      return new TypeRef(text, type.asString(), List.of());
    }
    if (type instanceof ArrayType array) {
      TypeRef component = typeRef(array.getComponentType(), resolver);
      String name = component.qualifiedName() == null ? null : component.qualifiedName() + "[]";
      return new TypeRef(text, name, component.typeArguments());
    }
    if (type instanceof ClassOrInterfaceType classType) {
      List<TypeRef> arguments = new ArrayList<>();
      classType
          .getTypeArguments()
          .ifPresent(args -> args.forEach(arg -> arguments.add(typeRef(arg, resolver))));
      return new TypeRef(
          text, resolver.scopedTo(classType).resolveName(classType.getNameWithScope()), arguments);
    }
    if (type instanceof WildcardType wildcard) {
      List<TypeRef> bounds = new ArrayList<>();
      wildcard.getExtendedType().ifPresent(t -> bounds.add(typeRef(t, resolver)));
      wildcard.getSuperType().ifPresent(t -> bounds.add(typeRef(t, resolver)));
      return new TypeRef(text, null, bounds);
    }
    return new TypeRef(text, null, List.of());
  }

  public static List<AnnotationModel> annotations(
      NodeList<AnnotationExpr> annotations, TypeNameResolver resolver) {
    return annotations.stream().map(a -> annotation(a, resolver)).toList();
  }

  private static AnnotationModel annotation(AnnotationExpr annotation, TypeNameResolver resolver) {
    Map<String, String> attributes = new LinkedHashMap<>();
    if (annotation instanceof SingleMemberAnnotationExpr single) {
      attributes.put("value", single.getMemberValue().toString());
    } else if (annotation instanceof NormalAnnotationExpr normal) {
      for (MemberValuePair pair : normal.getPairs()) {
        attributes.put(pair.getNameAsString(), pair.getValue().toString());
      }
    }
    String name = annotation.getNameAsString();
    return new AnnotationModel(name, resolver.scopedTo(annotation).resolveName(name), attributes);
  }

  private static MethodModel callable(
      CallableDeclaration<?> callable, TypeRef returnType, TypeNameResolver resolver) {
    List<ParameterModel> parameters =
        callable.getParameters().stream()
            .map(
                p ->
                    new ParameterModel(
                        p.getNameAsString(),
                        typeRef(p.getType(), resolver),
                        annotations(p.getAnnotations(), resolver)))
            .toList();
    return new MethodModel(
        callable.getNameAsString(),
        returnType,
        parameters,
        modifiers(callable),
        annotations(callable.getAnnotations(), resolver),
        javadoc(callable),
        comment(callable),
        bodyComments(callable),
        MemberOrigin.SOURCE,
        null);
  }

  /** Comments inside a method or constructor body, in source order. */
  private static List<String> bodyComments(CallableDeclaration<?> callable) {
    Optional<? extends Node> body =
        callable instanceof MethodDeclaration method
            ? method.getBody()
            : callable instanceof ConstructorDeclaration constructor
                ? Optional.of(constructor.getBody())
                : Optional.empty();
    return body.map(
            b ->
                b.getAllContainedComments().stream()
                    .sorted(
                        Comparator.comparing(
                            c -> c.getBegin().orElse(Position.HOME), Position::compareTo))
                    .map(c -> c.getContent().strip())
                    .toList())
        .orElse(List.of());
  }

  private static TypeKind kind(TypeDeclaration<?> type) {
    if (type instanceof ClassOrInterfaceDeclaration declaration) {
      return declaration.isInterface() ? TypeKind.INTERFACE : TypeKind.CLASS;
    }
    if (type instanceof EnumDeclaration) {
      return TypeKind.ENUM;
    }
    if (type instanceof RecordDeclaration) {
      return TypeKind.RECORD;
    }
    if (type instanceof AnnotationDeclaration) {
      return TypeKind.ANNOTATION;
    }
    return TypeKind.CLASS;
  }

  private static List<String> modifiers(NodeWithModifiers<?> node) {
    return node.getModifiers().stream().map(Modifier::getKeyword).map(k -> k.asString()).toList();
  }

  private static String javadoc(BodyDeclaration<?> declaration) {
    return declaration
        .getComment()
        .filter(Comment::isJavadocComment)
        .map(c -> ((JavadocComment) c).parse().toText().strip())
        .orElse(null);
  }

  private static String comment(BodyDeclaration<?> declaration) {
    Optional<Comment> comment = declaration.getComment();
    return comment.filter(c -> !c.isJavadocComment()).map(c -> c.getContent().strip()).orElse(null);
  }

  private static String importText(ImportDeclaration declaration) {
    return (declaration.isStatic() ? "static " : "")
        + declaration.getNameAsString()
        + (declaration.isAsterisk() ? ".*" : "");
  }
}
