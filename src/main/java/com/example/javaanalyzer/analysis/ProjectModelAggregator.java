package com.example.javaanalyzer.analysis;

import com.example.javaanalyzer.ir.AnalysisIssue;
import com.example.javaanalyzer.ir.BeanDefinitionModel;
import com.example.javaanalyzer.ir.ComponentModel;
import com.example.javaanalyzer.ir.ConfigurationPropertiesModel;
import com.example.javaanalyzer.ir.DependencyModel;
import com.example.javaanalyzer.ir.DependencyResolution;
import com.example.javaanalyzer.ir.EndpointModel;
import com.example.javaanalyzer.ir.EntityModel;
import com.example.javaanalyzer.ir.InjectionModel;
import com.example.javaanalyzer.ir.IssueKind;
import com.example.javaanalyzer.ir.IssueSeverity;
import com.example.javaanalyzer.ir.MapperModel;
import com.example.javaanalyzer.ir.PackageModel;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.RepositoryModel;
import com.example.javaanalyzer.ir.TypeKind;
import com.example.javaanalyzer.ir.TypeModel;
import com.example.javaanalyzer.ir.TypeRef;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Adds project-wide information derived from the IR alone, independent of any framework: the
 * resolved dependency of each injection, package information, circular dependencies (FR-14) and the
 * links between view names, templates and endpoints (FR-17, see {@link ViewLinker}). The cycle
 * graph is the DI graph, the same one FR-08 and FR-12 draw.
 *
 * <p>All lists are put in package order, so the model equals what is read back from the per-package
 * JSON files.
 *
 * <p>Generic injection types such as {@code List<Handler<Foo>>} keep only the beans that write
 * matching type arguments where they directly extend or implement the type; a bean whose arguments
 * cannot be read that way is recorded as one AMBIGUOUS entry instead of being guessed.
 *
 * <p>Known limitations: {@code @Qualifier} and {@code @Primary} are not used to narrow several
 * implementations (AMBIGUOUS); type arguments inherited through another generic type are not
 * substituted (UNKNOWN, see above); type arguments of {@code @Bean} return types are not compared;
 * dependencies that exist only through imports are not part of the graph.
 */
final class ProjectModelAggregator {

  private static final String ABSTRACT_MODIFIER = "abstract";
  private static final String UNBOUNDED_WILDCARD = "?";

  /** Whether a bean's type arguments for a generic injection type match the requested ones. */
  private enum TypeArgumentMatch {
    MATCH,
    MISMATCH,
    UNKNOWN
  }

  private final ProjectModel model;
  private final Map<String, TypeModel> typesByName;
  private final Set<String> repositoryNames;
  private final Set<String> managedTypeNames;
  private final List<String> beanReturnTypeNames;
  private final Set<String> beanCandidateNames;

  private ProjectModelAggregator(ProjectModel model) {
    this.model = model;
    this.typesByName =
        model.types().stream()
            .collect(Collectors.toMap(TypeModel::qualifiedName, Function.identity()));
    this.repositoryNames =
        model.repositories().stream().map(RepositoryModel::typeName).collect(Collectors.toSet());
    this.managedTypeNames = new TreeSet<>(repositoryNames);
    model.components().forEach(component -> managedTypeNames.add(component.typeName()));
    this.beanReturnTypeNames =
        model.beanDefinitions().stream()
            .map(bean -> bean.returnType().qualifiedName())
            .filter(Objects::nonNull)
            .toList();
    this.beanCandidateNames = new TreeSet<>(managedTypeNames);
    beanReturnTypeNames.stream().filter(typesByName::containsKey).forEach(beanCandidateNames::add);
  }

  static ProjectModel aggregate(ProjectModel model) {
    return new ProjectModelAggregator(model).aggregate();
  }

  private ProjectModel aggregate() {
    List<DependencyModel> dependencies = inPackageOrder(resolveAll(), DependencyModel::fromType);
    ViewLinker views = new ViewLinker(model.endpoints(), model.views());
    List<PackageModel> packages = packages(dependencies);

    List<AnalysisIssue> issues = new ArrayList<>(model.issues());
    issues.addAll(cycleIssues("コンポーネント", typeGraph(dependencies)));
    issues.addAll(cycleIssues("パッケージ", packageGraph(packages)));

    return new ProjectModel(
        model.rootPackage(),
        inPackageOrder(model.types(), TypeModel::qualifiedName),
        inPackageOrder(model.components(), ComponentModel::typeName),
        inPackageOrder(views.linkedEndpoints(), EndpointModel::controllerType),
        inPackageOrder(model.entities(), EntityModel::typeName),
        inPackageOrder(model.repositories(), RepositoryModel::typeName),
        inPackageOrder(model.beanDefinitions(), BeanDefinitionModel::declaringType),
        inPackageOrder(model.configurationProperties(), ConfigurationPropertiesModel::typeName),
        mappersInPackageOrder(),
        views.linkedViews(),
        dependencies,
        model.calls(),
        packages,
        issues);
  }

  /** Mappers matching no interface belong to no package, so they come last. */
  private List<MapperModel> mappersInPackageOrder() {
    List<MapperModel> mappers =
        new ArrayList<>(
            inPackageOrder(
                model.mappers().stream().filter(m -> m.interfaceType() != null).toList(),
                MapperModel::interfaceType));
    model.mappers().stream().filter(m -> m.interfaceType() == null).forEach(mappers::add);
    return mappers;
  }

  private <T> List<T> inPackageOrder(List<T> items, Function<T, String> typeNameOf) {
    return items.stream()
        .sorted(Comparator.comparing(item -> packageOf(typeNameOf.apply(item))))
        .toList();
  }

  private String packageOf(String typeName) {
    return typesByName.get(typeName).packageName();
  }

  private List<DependencyModel> resolveAll() {
    List<DependencyModel> dependencies = new ArrayList<>();
    for (ComponentModel component : model.components()) {
      for (InjectionModel injection : component.injections()) {
        dependencies.addAll(resolve(component.typeName(), injection));
      }
    }
    return dependencies;
  }

  private List<DependencyModel> resolve(String fromType, InjectionModel injection) {
    String beanTypeName = injection.beanType().qualifiedName();
    if (beanTypeName == null) {
      return List.of(dependency(fromType, injection, null, DependencyResolution.UNRESOLVED));
    }
    TypeModel target = typesByName.get(beanTypeName);
    if (target == null && isUnderRootPackage(beanTypeName)) {
      // A name under the root package without IR belongs to a source file that failed to parse.
      return List.of(dependency(fromType, injection, null, DependencyResolution.UNRESOLVED));
    }
    return List.copyOf(
        injection.multipleBeans()
            ? resolveAllBeans(fromType, injection, beanTypeName, target)
            : List.of(resolveSingleBean(fromType, injection, beanTypeName, target)));
  }

  /**
   * Spring leaves a bean out of a collection it is injecting itself, e.g. in a composite.
   *
   * @param target IR of the bean type; null when the type is outside the project
   */
  private List<DependencyModel> resolveAllBeans(
      String fromType, InjectionModel injection, String beanTypeName, TypeModel target) {
    List<String> subtypes =
        beanCandidateNames.stream()
            .filter(name -> !name.equals(fromType) && isSubtypeOf(name, beanTypeName))
            .toList();
    List<String> beans =
        subtypes.stream()
            .filter(
                name -> typeArgumentMatch(name, injection.beanType()) == TypeArgumentMatch.MATCH)
            .toList();
    boolean anyUnknown =
        subtypes.stream()
            .anyMatch(
                name -> typeArgumentMatch(name, injection.beanType()) == TypeArgumentMatch.UNKNOWN);
    if (beans.isEmpty() && anyUnknown) {
      return List.of(dependency(fromType, injection, null, DependencyResolution.AMBIGUOUS));
    }
    if (beans.isEmpty()) {
      return List.of(
          target == null
              ? dependency(fromType, injection, null, DependencyResolution.EXTERNAL)
              : dependency(fromType, injection, beanTypeName, DependencyResolution.PROJECT_TYPE));
    }
    List<DependencyModel> dependencies = new ArrayList<>();
    beans.forEach(
        bean ->
            dependencies.add(
                dependency(fromType, injection, bean, DependencyResolution.COLLECTION_ELEMENT)));
    if (anyUnknown) {
      // Beans whose type arguments cannot be read stay visible without guessing their membership.
      dependencies.add(dependency(fromType, injection, null, DependencyResolution.AMBIGUOUS));
    }
    return dependencies;
  }

  /**
   * Compares the type arguments of a generic injection type (e.g. {@code Handler<Foo>}) with those
   * the candidate writes where it directly extends or implements that type. Spring injects only
   * beans with matching arguments. When the candidate inherits the type through another generic
   * type, or an argument is unresolved or a bounded wildcard, the match is UNKNOWN.
   */
  private TypeArgumentMatch typeArgumentMatch(String candidateName, TypeRef requested) {
    if (requested.typeArguments().isEmpty()) {
      return TypeArgumentMatch.MATCH;
    }
    TypeModel candidate = typesByName.get(candidateName);
    if (candidate == null) {
      return TypeArgumentMatch.UNKNOWN;
    }
    List<TypeRef> superTypes = new ArrayList<>(candidate.interfaces());
    if (candidate.superClass() != null) {
      superTypes.add(candidate.superClass());
    }
    return superTypes.stream()
        .filter(superType -> requested.qualifiedName().equals(superType.qualifiedName()))
        .findFirst()
        .map(superType -> argumentsMatch(requested.typeArguments(), superType.typeArguments()))
        .orElse(TypeArgumentMatch.UNKNOWN);
  }

  private static TypeArgumentMatch argumentsMatch(List<TypeRef> requested, List<TypeRef> actual) {
    if (requested.size() != actual.size()) {
      return TypeArgumentMatch.UNKNOWN;
    }
    TypeArgumentMatch result = TypeArgumentMatch.MATCH;
    for (int i = 0; i < requested.size(); i++) {
      TypeArgumentMatch match = argumentMatch(requested.get(i), actual.get(i));
      if (match == TypeArgumentMatch.MISMATCH) {
        return match;
      }
      if (match == TypeArgumentMatch.UNKNOWN) {
        result = match;
      }
    }
    return result;
  }

  private static TypeArgumentMatch argumentMatch(TypeRef requested, TypeRef actual) {
    if (UNBOUNDED_WILDCARD.equals(requested.text())) {
      return TypeArgumentMatch.MATCH;
    }
    if (requested.qualifiedName() == null || actual.qualifiedName() == null) {
      return TypeArgumentMatch.UNKNOWN;
    }
    if (!requested.qualifiedName().equals(actual.qualifiedName())) {
      return TypeArgumentMatch.MISMATCH;
    }
    return requested.typeArguments().isEmpty()
        ? TypeArgumentMatch.MATCH
        : argumentsMatch(requested.typeArguments(), actual.typeArguments());
  }

  /**
   * @param target IR of the bean type; null when the type is outside the project, where a project
   *     type may still implement it, e.g. a {@code UserDetailsService} of a library
   */
  private DependencyModel resolveSingleBean(
      String fromType, InjectionModel injection, String targetName, TypeModel target) {
    if (target != null && (!isAbstractType(target) || repositoryNames.contains(targetName))) {
      return dependency(fromType, injection, targetName, DependencyResolution.PROJECT_TYPE);
    }
    List<String> implementations =
        beanCandidateNames.stream()
            .filter(name -> !name.equals(targetName) && isSubtypeOf(name, targetName))
            .filter(
                name -> typeArgumentMatch(name, injection.beanType()) != TypeArgumentMatch.MISMATCH)
            .toList();
    if (target == null && implementations.isEmpty()) {
      return dependency(fromType, injection, null, DependencyResolution.EXTERNAL);
    }
    long beansOfTargetType = beanReturnTypeNames.stream().filter(targetName::equals).count();
    if (implementations.size() + beansOfTargetType > 1) {
      return dependency(fromType, injection, null, DependencyResolution.AMBIGUOUS);
    }
    if (implementations.size() == 1) {
      return dependency(
          fromType, injection, implementations.get(0), DependencyResolution.SINGLE_IMPLEMENTATION);
    }
    // E.g. a MyBatis mapper or a Feign client: the interface itself is what the source shows.
    return dependency(fromType, injection, targetName, DependencyResolution.PROJECT_TYPE);
  }

  private static DependencyModel dependency(
      String fromType,
      InjectionModel injection,
      String resolvedType,
      DependencyResolution resolution) {
    return new DependencyModel(
        fromType,
        injection.memberName(),
        injection.kind(),
        injection.type(),
        resolvedType,
        resolution);
  }

  private boolean isUnderRootPackage(String typeName) {
    return !model.rootPackage().isEmpty() && typeName.startsWith(model.rootPackage() + ".");
  }

  private static boolean isAbstractType(TypeModel type) {
    return type.kind() == TypeKind.INTERFACE || type.modifiers().contains(ABSTRACT_MODIFIER);
  }

  private boolean isSubtypeOf(String typeName, String ancestorName) {
    Deque<String> pending = new ArrayDeque<>(List.of(typeName));
    Set<String> visited = new HashSet<>();
    while (!pending.isEmpty()) {
      String current = pending.pop();
      if (current.equals(ancestorName)) {
        return true;
      }
      TypeModel type = typesByName.get(current);
      if (type != null && visited.add(current)) {
        superTypeNames(type).forEach(pending::push);
      }
    }
    return false;
  }

  private static List<String> superTypeNames(TypeModel type) {
    List<String> names = new ArrayList<>();
    if (type.superClass() != null && type.superClass().qualifiedName() != null) {
      names.add(type.superClass().qualifiedName());
    }
    type.interfaces().stream()
        .map(TypeRef::qualifiedName)
        .filter(Objects::nonNull)
        .forEach(names::add);
    return names;
  }

  private List<PackageModel> packages(List<DependencyModel> dependencies) {
    Map<String, Set<String>> typeNames = new TreeMap<>();
    Map<String, Set<String>> dependsOn = new TreeMap<>();
    for (TypeModel type : model.types()) {
      typeNames
          .computeIfAbsent(type.packageName(), name -> new TreeSet<>())
          .add(type.qualifiedName());
      dependsOn.computeIfAbsent(type.packageName(), name -> new TreeSet<>());
    }
    for (DependencyModel dependency : dependencies) {
      if (dependency.resolvedType() != null) {
        String fromPackage = packageOf(dependency.fromType());
        String toPackage = packageOf(dependency.resolvedType());
        if (!fromPackage.equals(toPackage)) {
          dependsOn.get(fromPackage).add(toPackage);
        }
      }
    }
    return typeNames.entrySet().stream()
        .map(
            entry ->
                new PackageModel(
                    entry.getKey(),
                    List.copyOf(entry.getValue()),
                    List.copyOf(dependsOn.get(entry.getKey()))))
        .toList();
  }

  private static Map<String, Set<String>> typeGraph(List<DependencyModel> dependencies) {
    Map<String, Set<String>> graph = new TreeMap<>();
    for (DependencyModel dependency : dependencies) {
      if (dependency.resolvedType() != null) {
        graph
            .computeIfAbsent(dependency.fromType(), name -> new LinkedHashSet<>())
            .add(dependency.resolvedType());
      }
    }
    return graph;
  }

  private static Map<String, Set<String>> packageGraph(List<PackageModel> packages) {
    Map<String, Set<String>> graph = new TreeMap<>();
    packages.forEach(pkg -> graph.put(pkg.name(), new LinkedHashSet<>(pkg.dependsOn())));
    return graph;
  }

  private static List<AnalysisIssue> cycleIssues(String level, Map<String, Set<String>> graph) {
    return CycleDetector.findCycles(graph).stream()
        .map(
            members ->
                new AnalysisIssue(
                    IssueKind.CIRCULAR_DEPENDENCY,
                    IssueSeverity.WARNING,
                    null,
                    null,
                    level + "間の循環依存を検出しました: " + String.join(", ", members)))
        .toList();
  }
}
