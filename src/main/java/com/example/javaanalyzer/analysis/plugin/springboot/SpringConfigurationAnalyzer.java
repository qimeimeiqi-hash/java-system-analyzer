package com.example.javaanalyzer.analysis.plugin.springboot;

import com.example.javaanalyzer.analysis.AnalysisContext;
import com.example.javaanalyzer.analysis.AnnotationSupport;
import com.example.javaanalyzer.analysis.ParsedType;
import com.example.javaanalyzer.analysis.StructureExtractor;
import com.example.javaanalyzer.analysis.TypeNameResolver;
import com.example.javaanalyzer.ir.BeanDefinitionModel;
import com.example.javaanalyzer.ir.ConfigurationPropertiesModel;
import com.example.javaanalyzer.ir.ParameterModel;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import java.util.List;
import java.util.Optional;

/**
 * Lists where a project configures itself (FR-16): {@code @Bean} methods in any type and
 * {@code @ConfigurationProperties} on types and {@code @Bean} methods. Only what is written in the
 * annotations is recorded; default bean names are not guessed.
 *
 * <p>Known limitations: {@code @Bean} method parameters are kept in the IR but are not dependencies
 * of the declaring class (FR-08 covers constructor and field injection), and the contents of beans,
 * such as security rules, are not interpreted.
 */
class SpringConfigurationAnalyzer {

  void analyze(AnalysisContext context) {
    for (ParsedType parsed : context.parsedTypes()) {
      TypeNameResolver resolver = parsed.resolver();
      configurationProperties(parsed, parsed.declaration(), null)
          .ifPresent(context::addConfigurationProperties);
      for (MethodDeclaration method : parsed.declaration().getMethods()) {
        if (AnnotationSupport.has(method, resolver, AnnotationNames.BEAN)) {
          context.addBeanDefinition(beanDefinition(parsed, method));
          configurationProperties(parsed, method, method.getNameAsString())
              .ifPresent(context::addConfigurationProperties);
        }
      }
    }
  }

  private static BeanDefinitionModel beanDefinition(ParsedType parsed, MethodDeclaration method) {
    TypeNameResolver resolver = parsed.resolver();
    return new BeanDefinitionModel(
        parsed.qualifiedName(),
        method.getNameAsString(),
        beanNames(method, resolver),
        StructureExtractor.typeRef(method.getType(), resolver),
        method.getParameters().stream().map(p -> parameter(p, resolver)).toList());
  }

  private static List<String> beanNames(MethodDeclaration method, TypeNameResolver resolver) {
    Optional<AnnotationExpr> bean = AnnotationSupport.find(method, resolver, AnnotationNames.BEAN);
    return bean
        .flatMap(
            annotation ->
                AnnotationSupport.attribute(annotation, "name")
                    .or(() -> AnnotationSupport.attribute(annotation, "value")))
        .map(AnnotationSupport::values)
        .orElse(List.of())
        .stream()
        .map(SpringConfigurationAnalyzer::valueOrRawExpression)
        .toList();
  }

  private static String valueOrRawExpression(Expression expression) {
    return AnnotationSupport.stringValue(expression).orElseGet(expression::toString);
  }

  private static ParameterModel parameter(Parameter parameter, TypeNameResolver resolver) {
    return new ParameterModel(
        parameter.getNameAsString(),
        StructureExtractor.typeRef(parameter.getType(), resolver),
        StructureExtractor.annotations(parameter.getAnnotations(), resolver));
  }

  private static Optional<ConfigurationPropertiesModel> configurationProperties(
      ParsedType parsed, NodeWithAnnotations<?> node, String beanMethodName) {
    return AnnotationSupport.find(node, parsed.resolver(), AnnotationNames.CONFIGURATION_PROPERTIES)
        .map(
            annotation -> {
              Optional<Expression> prefix =
                  AnnotationSupport.attribute(annotation, "prefix")
                      .or(() -> AnnotationSupport.attribute(annotation, "value"));
              return new ConfigurationPropertiesModel(
                  parsed.qualifiedName(),
                  beanMethodName,
                  prefix.map(SpringConfigurationAnalyzer::valueOrRawExpression).orElse(null),
                  prefix.isEmpty() || prefix.flatMap(AnnotationSupport::stringValue).isPresent());
            });
  }
}
