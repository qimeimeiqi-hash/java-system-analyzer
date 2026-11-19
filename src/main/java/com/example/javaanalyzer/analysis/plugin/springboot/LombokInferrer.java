package com.example.javaanalyzer.analysis.plugin.springboot;

import com.example.javaanalyzer.analysis.AnalysisContext;
import com.example.javaanalyzer.analysis.AnnotationSupport;
import com.example.javaanalyzer.analysis.ParsedType;
import com.example.javaanalyzer.analysis.TypeNameResolver;
import com.example.javaanalyzer.ir.FieldModel;
import com.example.javaanalyzer.ir.MemberOrigin;
import com.example.javaanalyzer.ir.MethodModel;
import com.example.javaanalyzer.ir.ParameterModel;
import com.example.javaanalyzer.ir.TypeModel;
import com.example.javaanalyzer.ir.TypeRef;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Adds members that Lombok generates at compile time to each type's IR (FR-03, top priority), so
 * that e.g. {@code @RequiredArgsConstructor} constructor injection is not silently lost. This is a
 * deterministic application of the Lombok annotation specification, not a guess.
 *
 * <p>Supported: {@code @Getter}, {@code @Setter} (class and field level, incl. {@code
 * AccessLevel}), {@code @Data}, {@code @Value}, {@code @RequiredArgsConstructor},
 * {@code @AllArgsConstructor}, {@code @NoArgsConstructor}, {@code @Builder} on classes,
 * {@code @ToString}, {@code @EqualsAndHashCode}. Classes are processed; enums only for
 * {@code @Getter}/{@code @Setter}; interfaces, records and annotation types are skipped.
 *
 * <p>Known limitations: {@code lombok.config} (e.g. fluent accessors), {@code @Accessors}, log
 * annotations ({@code @Slf4j} etc.), {@code @With}, {@code @Builder} on constructors/methods,
 * {@code @SuperBuilder}, {@code @NonFinal}/{@code @FieldDefaults} and {@code staticConstructor} are
 * not handled. Generated members carry no annotations or parameter-level annotations.
 */
class LombokInferrer {

  private static final String DATA = "Data";
  private static final String VALUE = "Value";
  private static final String GETTER = "Getter";
  private static final String SETTER = "Setter";
  private static final String BUILDER = "Builder";
  private static final String TO_STRING = "ToString";
  private static final String EQUALS_AND_HASH_CODE = "EqualsAndHashCode";
  private static final String NO_ARGS = "NoArgsConstructor";
  private static final String ALL_ARGS = "AllArgsConstructor";
  private static final String REQUIRED_ARGS = "RequiredArgsConstructor";

  private static final TypeRef VOID = new TypeRef("void", "void", List.of());
  private static final TypeRef BOOLEAN = new TypeRef("boolean", "boolean", List.of());
  private static final TypeRef INT = new TypeRef("int", "int", List.of());
  private static final TypeRef STRING = new TypeRef("String", "java.lang.String", List.of());
  private static final TypeRef OBJECT = new TypeRef("Object", "java.lang.Object", List.of());

  private static final List<String> PUBLIC = List.of("public");
  private static final Map<String, List<String>> ACCESS_MODIFIERS =
      Map.of(
          "PUBLIC", PUBLIC,
          "PROTECTED", List.of("protected"),
          "PACKAGE", List.of(),
          "MODULE", List.of(),
          "PRIVATE", List.of("private"));

  void apply(AnalysisContext context) {
    for (ParsedType parsed : context.parsedTypes()) {
      TypeModel type = context.type(parsed.qualifiedName());
      if (type == null) {
        continue;
      }
      TypeDeclaration<?> declaration = parsed.declaration();
      boolean isClass = declaration instanceof ClassOrInterfaceDeclaration c && !c.isInterface();
      if (!isClass && !(declaration instanceof EnumDeclaration)) {
        continue;
      }
      TypeModel inferred = new TypeInference(type, declaration, parsed.resolver(), isClass).run();
      if (inferred != type) {
        context.replaceType(inferred);
      }
    }
  }

  /** A Lombok annotation found on a node, with its simple name and access modifiers. */
  private record Found(String simpleName, AnnotationExpr annotation) {

    /** Modifiers for the requested access, or empty when {@code AccessLevel.NONE}. */
    Optional<List<String>> modifiers(String attributeName) {
      Optional<String> level =
          AnnotationSupport.attribute(annotation, attributeName)
              .map(AnnotationSupport::constantName);
      if (level.isEmpty()) {
        return Optional.of(PUBLIC);
      }
      return Optional.ofNullable(ACCESS_MODIFIERS.get(level.get()));
    }
  }

  /** A non-static field together with its AST annotations. */
  private record Field(FieldModel model, FieldDeclaration declaration) {

    String name() {
      return model.name();
    }

    TypeRef type() {
      return model.type();
    }

    boolean declaredFinal() {
      return model.modifiers().contains("final");
    }
  }

  /** Inference for one type declaration. */
  private static final class TypeInference {

    private final TypeModel type;
    private final TypeDeclaration<?> declaration;
    private final TypeNameResolver resolver;
    private final boolean isClass;
    private final List<Field> fields = new ArrayList<>();
    private final List<MethodModel> existingConstructors;
    private final List<MethodModel> existingMethods;
    private final List<MethodModel> newConstructors = new ArrayList<>();
    private final List<MethodModel> newMethods = new ArrayList<>();
    private boolean valueClass;

    TypeInference(
        TypeModel type,
        TypeDeclaration<?> declaration,
        TypeNameResolver resolver,
        boolean isClass) {
      this.type = type;
      this.declaration = declaration;
      this.resolver = resolver;
      this.isClass = isClass;
      this.existingConstructors = new ArrayList<>(type.constructors());
      this.existingMethods = new ArrayList<>(type.methods());
      for (FieldDeclaration field : declaration.getFields()) {
        if (field.isStatic()) {
          continue;
        }
        for (VariableDeclarator variable : field.getVariables()) {
          type.fields().stream()
              .filter(f -> f.name().equals(variable.getNameAsString()))
              .findFirst()
              .ifPresent(model -> fields.add(new Field(model, field)));
        }
      }
    }

    TypeModel run() {
      Optional<Found> data = isClass ? find(declaration, DATA) : Optional.empty();
      Optional<Found> value = isClass ? find(declaration, VALUE) : Optional.empty();
      valueClass = value.isPresent();
      Optional<Found> classGetter = find(declaration, GETTER);
      Optional<Found> classSetter = find(declaration, SETTER);

      if (isClass) {
        inferConstructors(data, value);
      }
      Optional<Found> getterDefault = classGetter.or(() -> data).or(() -> value);
      Optional<Found> setterDefault = classSetter.or(() -> data);
      for (Field field : fields) {
        inferGetter(field, find(field.declaration(), GETTER).or(() -> getterDefault));
        inferSetter(field, find(field.declaration(), SETTER).or(() -> setterDefault));
      }
      if (isClass) {
        inferObjectMethods(data, value);
        find(declaration, BUILDER).ifPresent(this::inferBuilderMethod);
      }

      if (newConstructors.isEmpty() && newMethods.isEmpty()) {
        return type;
      }
      return type.withAdditionalMembers(newConstructors, newMethods);
    }

    private void inferConstructors(Optional<Found> data, Optional<Found> value) {
      Optional<Found> noArgs = find(declaration, NO_ARGS);
      Optional<Found> allArgs = find(declaration, ALL_ARGS);
      Optional<Found> requiredArgs = find(declaration, REQUIRED_ARGS);
      boolean explicit =
          noArgs.isPresent()
              || allArgs.isPresent()
              || requiredArgs.isPresent()
              || !declaration.getConstructors().isEmpty();

      noArgs.ifPresent(found -> addConstructor(found, List.of()));
      allArgs.ifPresent(found -> addConstructor(found, allArgsFields()));
      requiredArgs.ifPresent(found -> addConstructor(found, requiredArgsFields()));
      if (explicit) {
        return;
      }
      // @Builder's constructor takes precedence; @Data/@Value then see an existing constructor.
      Optional<Found> builder = find(declaration, BUILDER);
      if (builder.isPresent()) {
        addConstructor(builder.get(), List.of(), allArgsFields());
      } else if (value.isPresent()) {
        addConstructor(value.get(), allArgsFields());
      } else if (data.isPresent()) {
        addConstructor(data.get(), requiredArgsFields());
      }
    }

    private void inferGetter(Field field, Optional<Found> annotation) {
      if (annotation.isEmpty()) {
        return;
      }
      annotation
          .get()
          .modifiers("value")
          .ifPresent(
              modifiers ->
                  addMethod(
                      getterName(field),
                      field.type(),
                      List.of(),
                      modifiers,
                      annotation.get().simpleName()));
    }

    private void inferSetter(Field field, Optional<Found> annotation) {
      if (annotation.isEmpty() || isFinal(field)) {
        return;
      }
      ParameterModel parameter = new ParameterModel(field.name(), field.type(), List.of());
      annotation
          .get()
          .modifiers("value")
          .ifPresent(
              modifiers ->
                  addMethod(
                      "set" + accessorSuffix(field),
                      VOID,
                      List.of(parameter),
                      modifiers,
                      annotation.get().simpleName()));
    }

    private void inferObjectMethods(Optional<Found> data, Optional<Found> value) {
      Optional<Found> dataOrValue = data.or(() -> value);
      find(declaration, TO_STRING)
          .or(() -> dataOrValue)
          .ifPresent(found -> addMethod("toString", STRING, List.of(), PUBLIC, found.simpleName()));
      find(declaration, EQUALS_AND_HASH_CODE)
          .or(() -> dataOrValue)
          .ifPresent(
              found -> {
                ParameterModel other = new ParameterModel("o", OBJECT, List.of());
                addMethod("equals", BOOLEAN, List.of(other), PUBLIC, found.simpleName());
                addMethod("hashCode", INT, List.of(), PUBLIC, found.simpleName());
              });
    }

    private void inferBuilderMethod(Found builder) {
      String builderName = type.simpleName() + BUILDER;
      TypeRef builderType =
          new TypeRef(builderName, type.qualifiedName() + "." + builderName, List.of());
      addMethod("builder", builderType, List.of(), List.of("public", "static"), BUILDER);
    }

    /** All non-static fields except initialized final ones. */
    private List<Field> allArgsFields() {
      return fields.stream().filter(f -> !(isFinal(f) && f.model().initialized())).toList();
    }

    /** Uninitialized fields that are final or annotated {@code @lombok.NonNull}. */
    private List<Field> requiredArgsFields() {
      Predicate<Field> required = f -> isFinal(f) || find(f.declaration(), "NonNull").isPresent();
      return fields.stream().filter(f -> !f.model().initialized()).filter(required).toList();
    }

    private boolean isFinal(Field field) {
      return valueClass || field.declaredFinal();
    }

    private void addConstructor(Found annotation, List<Field> parameters) {
      annotation
          .modifiers("access")
          .ifPresent(modifiers -> addConstructor(annotation, modifiers, parameters));
    }

    private void addConstructor(Found annotation, List<String> modifiers, List<Field> parameters) {
      List<String> parameterTypes = parameters.stream().map(f -> f.type().text()).toList();
      boolean exists =
          existingConstructors.stream()
              .anyMatch(
                  c ->
                      c.parameters().stream()
                          .map(p -> p.type().text())
                          .toList()
                          .equals(parameterTypes));
      if (exists) {
        return;
      }
      MethodModel constructor =
          generated(
              type.simpleName(),
              null,
              parameterModels(parameters),
              modifiers,
              annotation.simpleName());
      existingConstructors.add(constructor);
      newConstructors.add(constructor);
    }

    private void addMethod(
        String name,
        TypeRef returnType,
        List<ParameterModel> parameters,
        List<String> modifiers,
        String generatedBy) {
      boolean exists =
          existingMethods.stream()
              .anyMatch(m -> m.name().equals(name) && m.parameters().size() == parameters.size());
      if (exists) {
        return;
      }
      MethodModel method = generated(name, returnType, parameters, modifiers, generatedBy);
      existingMethods.add(method);
      newMethods.add(method);
    }

    private Optional<Found> find(NodeWithAnnotations<?> node, String simpleName) {
      return AnnotationSupport.find(node, resolver, AnnotationNames.lombok(simpleName))
          .map(annotation -> new Found(simpleName, annotation));
    }
  }

  private static MethodModel generated(
      String name,
      TypeRef returnType,
      List<ParameterModel> parameters,
      List<String> modifiers,
      String generatedBy) {
    return new MethodModel(
        name,
        returnType,
        parameters,
        modifiers,
        List.of(),
        null,
        null,
        List.of(),
        MemberOrigin.LOMBOK,
        generatedBy);
  }

  private static List<ParameterModel> parameterModels(List<Field> fields) {
    return fields.stream().map(f -> new ParameterModel(f.name(), f.type(), List.of())).toList();
  }

  private static boolean isPrimitiveBoolean(Field field) {
    return "boolean".equals(field.type().text());
  }

  /**
   * {@code isX} for primitive boolean (keeping an existing {@code is} prefix), else {@code getX}.
   */
  private static String getterName(Field field) {
    if (isPrimitiveBoolean(field)) {
      return hasIsPrefix(field.name()) ? field.name() : "is" + capitalize(field.name());
    }
    return "get" + capitalize(field.name());
  }

  /** Name part after {@code get/is/set}: {@code isActive} (primitive boolean) becomes Active. */
  private static String accessorSuffix(Field field) {
    if (isPrimitiveBoolean(field) && hasIsPrefix(field.name())) {
      return field.name().substring(2);
    }
    return capitalize(field.name());
  }

  private static boolean hasIsPrefix(String name) {
    return name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2));
  }

  private static String capitalize(String name) {
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }
}
