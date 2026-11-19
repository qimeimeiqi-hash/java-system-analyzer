package com.example.javaanalyzer.analysis.plugin.springboot;

import com.example.javaanalyzer.analysis.AnalysisContext;
import com.example.javaanalyzer.analysis.ParsedType;
import com.example.javaanalyzer.analysis.plugin.AnalyzerPlugin;
import org.springframework.stereotype.Component;

/**
 * Spring Boot / Spring MVC / Spring Data JPA / Lombok analyzer (the only plugin in V1). Lombok runs
 * first so that later steps see generated constructors (constructor injection).
 */
@Component
public class SpringBootPlugin implements AnalyzerPlugin {

  private final LombokInferrer lombokInferrer = new LombokInferrer();
  private final SpringComponentAnalyzer componentAnalyzer = new SpringComponentAnalyzer();
  private final RequestMappingAnalyzer requestMappingAnalyzer = new RequestMappingAnalyzer();
  private final JpaEntityAnalyzer jpaEntityAnalyzer = new JpaEntityAnalyzer();
  private final SpringDataRepositoryAnalyzer repositoryAnalyzer =
      new SpringDataRepositoryAnalyzer();
  private final SpringConfigurationAnalyzer configurationAnalyzer =
      new SpringConfigurationAnalyzer();

  @Override
  public String name() {
    return "Spring Boot";
  }

  @Override
  public boolean supports(AnalysisContext context) {
    return context.parsedTypes().stream()
        .map(ParsedType::qualifiedName)
        .map(context::type)
        .flatMap(type -> type.imports().stream())
        .anyMatch(
            imported ->
                imported.startsWith("org.springframework.")
                    || imported.startsWith("jakarta.persistence.")
                    || imported.startsWith("javax.persistence.")
                    || imported.startsWith(AnnotationNames.LOMBOK));
  }

  @Override
  public void analyze(AnalysisContext context) {
    lombokInferrer.apply(context);
    componentAnalyzer.analyze(context);
    requestMappingAnalyzer.analyze(context);
    jpaEntityAnalyzer.analyze(context);
    repositoryAnalyzer.analyze(context);
    configurationAnalyzer.analyze(context);
    context.qualifyAnnotations(AnnotationNames.recognized());
  }
}
