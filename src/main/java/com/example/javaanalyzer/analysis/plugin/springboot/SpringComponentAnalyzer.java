package com.example.javaanalyzer.analysis.plugin.springboot;

import com.example.javaanalyzer.analysis.AnalysisContext;
import com.example.javaanalyzer.analysis.AnnotationSupport;
import com.example.javaanalyzer.analysis.KnownTypes;
import com.example.javaanalyzer.analysis.ParsedType;
import com.example.javaanalyzer.analysis.StructureExtractor;
import com.example.javaanalyzer.analysis.TypeNameResolver;
import com.example.javaanalyzer.ir.AnnotationModel;
import com.example.javaanalyzer.ir.ComponentModel;
import com.example.javaanalyzer.ir.ComponentRole;
import com.example.javaanalyzer.ir.InjectionKind;
import com.example.javaanalyzer.ir.InjectionModel;
import com.example.javaanalyzer.ir.MemberOrigin;
import com.example.javaanalyzer.ir.MethodModel;
import com.example.javaanalyzer.ir.ParameterModel;
import com.example.javaanalyzer.ir.TypeModel;
import com.example.javaanalyzer.ir.TypeRef;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Detects Spring components and their injected dependencies (input for FR-08). Constructor
 * injection is read from the IR so that Lombok-generated constructors are included.
 *
 * <p>Injection wrappers are removed to find the injected beans: single-bean wrappers such as {@code
 * Optional<Foo>}, and {@code List<Foo>}, {@code Set<Foo>}, {@code Collection<Foo>}, {@code
 * Map<String, Foo>} and {@code Foo[]}, which inject every matching bean.
 *
 * <p>Known limitations: custom stereotype meta-annotations are not detected, including {@code
 * SpringBootApplication}, so injection into the application class is not recorded.
 */
class SpringComponentAnalyzer {

  private static final String[] INJECT_ANNOTATIONS = {
    AnnotationNames.AUTOWIRED,
    "jakarta.inject.Inject",
    "javax.inject.Inject",
    "jakarta.annotation.Resource",
    "javax.annotation.Resource"
  };

  private static final String[] CONSTRUCTOR_INJECT_ANNOTATIONS = {
    AnnotationNames.AUTOWIRED, "jakarta.inject.Inject", "javax.inject.Inject"
  };

  private static final Set<String> SINGLE_BEAN_WRAPPERS =
      Set.of(
          "java.util.Optional",
          "org.springframework.beans.factory.ObjectProvider",
          "org.springframework.beans.factory.ObjectFactory",
          "jakarta.inject.Provider",
          "javax.inject.Provider");

  private static final Set<String> COLLECTION_TYPES =
      Set.of("java.util.List", "java.util.Set", "java.util.Collection");

  private static final Set<String> MAP_TYPES = Set.of("java.util.Map");
  private static final String STRING_TYPE = "java.lang.String";
  private static final String ARRAY_SUFFIX = "[]";

  void analyze(AnalysisContext context) {
    for (ParsedType parsed : context.parsedTypes()) {
      roleOf(parsed)
          .ifPresent(
              role ->
                  context.addComponent(
                      new ComponentModel(
                          parsed.qualifiedName(),
                          role,
                          injections(parsed, context.type(parsed.qualifiedName())))));
    }
  }

  static Optional<ComponentRole> roleOf(ParsedType parsed) {
    TypeDeclaration<?> type = parsed.declaration();
    TypeNameResolver resolver = parsed.resolver();
    if (AnnotationSupport.has(
        type, resolver, AnnotationNames.CONTROLLER, AnnotationNames.REST_CONTROLLER)) {
      return Optional.of(ComponentRole.CONTROLLER);
    }
    if (AnnotationSupport.has(type, resolver, AnnotationNames.SERVICE)) {
      return Optional.of(ComponentRole.SERVICE);
    }
    if (AnnotationSupport.has(type, resolver, AnnotationNames.REPOSITORY)) {
      return Optional.of(ComponentRole.REPOSITORY);
    }
    if (AnnotationSupport.has(type, resolver, AnnotationNames.CONFIGURATION)) {
      return Optional.of(ComponentRole.CONFIGURATION);
    }
    if (AnnotationSupport.has(type, resolver, AnnotationNames.COMPONENT)) {
      return Optional.of(ComponentRole.COMPONENT);
    }
    return Optional.empty();
  }

  private static List<InjectionModel> injections(ParsedType parsed, TypeModel type) {
    TypeNameResolver resolver = parsed.resolver();
    List<InjectionModel> injections = new ArrayList<>();

    for (FieldDeclaration field : parsed.declaration().getFields()) {
      if (field.isStatic() || !AnnotationSupport.has(field, resolver, INJECT_ANNOTATIONS)) {
        continue;
      }
      for (VariableDeclarator variable : field.getVariables()) {
        injections.add(
            injection(
                InjectionKind.FIELD,
                variable.getNameAsString(),
                StructureExtractor.typeRef(variable.getType(), resolver),
                MemberOrigin.SOURCE,
                qualifier(field, resolver),
                resolver));
      }
    }

    injectingConstructor(type, resolver)
        .ifPresent(
            constructor -> {
              for (ParameterModel parameter : constructor.parameters()) {
                injections.add(
                    injection(
                        InjectionKind.CONSTRUCTOR,
                        parameter.name(),
                        parameter.type(),
                        constructor.origin(),
                        qualifier(parameter.annotations(), resolver),
                        resolver));
              }
            });
    if (parsed.declaration() instanceof RecordDeclaration record && type.constructors().isEmpty()) {
      for (Parameter component : record.getParameters()) {
        injections.add(
            injection(
                InjectionKind.CONSTRUCTOR,
                component.getNameAsString(),
                StructureExtractor.typeRef(component.getType(), resolver),
                MemberOrigin.SOURCE,
                qualifier(component, resolver),
                resolver));
      }
    }

    for (MethodDeclaration method : parsed.declaration().getMethods()) {
      if (method.isStatic() || !AnnotationSupport.has(method, resolver, INJECT_ANNOTATIONS)) {
        continue;
      }
      for (Parameter parameter : method.getParameters()) {
        injections.add(
            injection(
                InjectionKind.SETTER,
                parameter.getNameAsString(),
                StructureExtractor.typeRef(parameter.getType(), resolver),
                MemberOrigin.SOURCE,
                qualifier(parameter, resolver),
                resolver));
      }
    }
    return injections;
  }

  private static InjectionModel injection(
      InjectionKind kind,
      String memberName,
      TypeRef type,
      MemberOrigin origin,
      String qualifier,
      TypeNameResolver resolver) {
    TypeRef beanType = type;
    boolean multipleBeans = false;
    for (TypeRef inner = wrappedType(beanType, resolver);
        inner != null;
        inner = wrappedType(beanType, resolver)) {
      multipleBeans |= injectsAllBeans(beanType, resolver);
      beanType = inner;
    }
    return new InjectionModel(kind, memberName, type, origin, qualifier, beanType, multipleBeans);
  }

  /** The type inside a Spring injection wrapper, wildcard or array; null when there is none. */
  private static TypeRef wrappedType(TypeRef type, TypeNameResolver resolver) {
    if (isArray(type)) {
      return new TypeRef(
          withoutArraySuffix(type.text()),
          type.qualifiedName() == null ? null : withoutArraySuffix(type.qualifiedName()),
          type.typeArguments());
    }
    List<TypeRef> arguments = type.typeArguments();
    if (isWildcard(type)
        || KnownTypes.match(type, resolver, SINGLE_BEAN_WRAPPERS).isPresent()
        || KnownTypes.match(type, resolver, COLLECTION_TYPES).isPresent()) {
      return arguments.size() == 1 ? arguments.get(0) : null;
    }
    return isStringKeyedMap(type, resolver) ? arguments.get(1) : null;
  }

  private static boolean injectsAllBeans(TypeRef wrapper, TypeNameResolver resolver) {
    return isArray(wrapper)
        || KnownTypes.match(wrapper, resolver, COLLECTION_TYPES).isPresent()
        || isStringKeyedMap(wrapper, resolver);
  }

  private static boolean isArray(TypeRef type) {
    return type.text().endsWith(ARRAY_SUFFIX);
  }

  private static String withoutArraySuffix(String name) {
    return name.substring(0, name.length() - ARRAY_SUFFIX.length());
  }

  private static boolean isWildcard(TypeRef type) {
    return type.qualifiedName() == null && type.text().startsWith("?");
  }

  private static boolean isStringKeyedMap(TypeRef type, TypeNameResolver resolver) {
    return KnownTypes.match(type, resolver, MAP_TYPES).isPresent()
        && type.typeArguments().size() == 2
        && STRING_TYPE.equals(type.typeArguments().get(0).qualifiedName());
  }

  /**
   * The only constructor, or the {@code @Autowired}/{@code @Inject} one when several exist (Spring
   * rules). A record without explicit constructors is handled by the caller: its implicit canonical
   * constructor is not in the IR.
   */
  private static Optional<MethodModel> injectingConstructor(
      TypeModel type, TypeNameResolver resolver) {
    List<MethodModel> constructors = type.constructors();
    if (constructors.size() == 1) {
      return Optional.of(constructors.get(0));
    }
    return constructors.stream()
        .filter(
            c ->
                c.annotations().stream()
                    .anyMatch(
                        a ->
                            Arrays.stream(CONSTRUCTOR_INJECT_ANNOTATIONS)
                                .anyMatch(name -> resolver.refersTo(a.name(), name))))
        .findFirst();
  }

  private static String qualifier(NodeWithAnnotations<?> node, TypeNameResolver resolver) {
    return AnnotationSupport.find(node, resolver, AnnotationNames.QUALIFIER)
        .flatMap(a -> AnnotationSupport.attribute(a, "value"))
        .flatMap(AnnotationSupport::stringValue)
        .orElse(null);
  }

  private static String qualifier(List<AnnotationModel> annotations, TypeNameResolver resolver) {
    return annotations.stream()
        .filter(a -> resolver.refersTo(a.name(), AnnotationNames.QUALIFIER))
        .map(a -> a.attributes().get("value"))
        .filter(value -> value != null && value.length() >= 2 && value.startsWith("\""))
        .map(value -> value.substring(1, value.length() - 1))
        .findFirst()
        .orElse(null);
  }
}
