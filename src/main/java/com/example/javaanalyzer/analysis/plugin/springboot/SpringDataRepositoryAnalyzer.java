package com.example.javaanalyzer.analysis.plugin.springboot;

import com.example.javaanalyzer.analysis.AnalysisContext;
import com.example.javaanalyzer.analysis.KnownTypes;
import com.example.javaanalyzer.analysis.ParsedType;
import com.example.javaanalyzer.analysis.StructureExtractor;
import com.example.javaanalyzer.ir.ComponentModel;
import com.example.javaanalyzer.ir.ComponentRole;
import com.example.javaanalyzer.ir.RepositoryModel;
import com.example.javaanalyzer.ir.TypeRef;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Detects Spring Data repositories (interfaces directly extending a Spring Data base interface) and
 * registers them as REPOSITORY components, since Spring creates them without {@code @Repository}.
 *
 * <p>Known limitation: repositories extending a project-defined base repository are not detected.
 * Must run after {@link SpringComponentAnalyzer} to avoid registering a component twice.
 */
class SpringDataRepositoryAnalyzer {

  private static final Set<String> BASE_REPOSITORIES =
      Set.of(
          "org.springframework.data.repository.Repository",
          "org.springframework.data.repository.CrudRepository",
          "org.springframework.data.repository.ListCrudRepository",
          "org.springframework.data.repository.PagingAndSortingRepository",
          "org.springframework.data.repository.ListPagingAndSortingRepository",
          "org.springframework.data.jpa.repository.JpaRepository");

  void analyze(AnalysisContext context) {
    Set<String> registeredComponents =
        context.toProjectModel().components().stream()
            .map(ComponentModel::typeName)
            .collect(Collectors.toSet());
    for (ParsedType parsed : context.parsedTypes()) {
      if (!(parsed.declaration() instanceof ClassOrInterfaceDeclaration declaration)
          || !declaration.isInterface()) {
        continue;
      }
      for (ClassOrInterfaceType extended : declaration.getExtendedTypes()) {
        TypeRef baseType = StructureExtractor.typeRef(extended, parsed.resolver());
        Optional<String> baseName =
            KnownTypes.match(baseType, parsed.resolver(), BASE_REPOSITORIES);
        if (baseName.isEmpty()) {
          continue;
        }
        List<TypeRef> arguments = baseType.typeArguments();
        context.addRepository(
            new RepositoryModel(
                parsed.qualifiedName(),
                new TypeRef(baseType.text(), baseName.get(), arguments),
                arguments.size() == 2 ? arguments.get(0) : null,
                arguments.size() == 2 ? arguments.get(1) : null));
        if (!registeredComponents.contains(parsed.qualifiedName())) {
          context.addComponent(
              new ComponentModel(parsed.qualifiedName(), ComponentRole.REPOSITORY, List.of()));
        }
        break;
      }
    }
  }
}
