package com.example.javaanalyzer.analysis.plugin.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.analysis.AnalysisContext;
import com.example.javaanalyzer.analysis.AnalysisTestSupport;
import com.example.javaanalyzer.ir.BeanDefinitionModel;
import com.example.javaanalyzer.ir.ConfigurationPropertiesModel;
import com.example.javaanalyzer.ir.ParameterModel;
import com.example.javaanalyzer.ir.ProjectModel;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpringConfigurationAnalyzerTest {

  @TempDir Path projectRoot;

  private ProjectModel analyze(String... sources) {
    AnalysisContext context = AnalysisTestSupport.contextOf(projectRoot, sources);
    new SpringConfigurationAnalyzer().analyze(context);
    return context.toProjectModel();
  }

  private BeanDefinitionModel onlyBean(String methodLine) {
    ProjectModel model =
        analyze(
            """
            package com.example.config;
            import javax.sql.DataSource;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            @Configuration
            public class DataConfig {
              %s
            }
            """
                .formatted(methodLine));
    assertThat(model.beanDefinitions()).hasSize(1);
    return model.beanDefinitions().get(0);
  }

  @Test
  @DisplayName("A @Bean method is listed with its declaring class, name and return type")
  void extractsBeanDefinition() {
    BeanDefinitionModel bean = onlyBean("@Bean public DataSource dataSource() { return null; }");

    assertThat(bean.declaringType()).isEqualTo("com.example.config.DataConfig");
    assertThat(bean.methodName()).isEqualTo("dataSource");
    assertThat(bean.returnType().qualifiedName()).isEqualTo("javax.sql.DataSource");
    assertThat(bean.beanNames()).isEmpty();
    assertThat(bean.parameters()).isEmpty();
  }

  @Test
  @DisplayName("A single-member bean name is recorded")
  void recordsSingleMemberBeanName() {
    assertThat(onlyBean("@Bean(\"main\") DataSource ds() { return null; }").beanNames())
        .containsExactly("main");
  }

  @Test
  @DisplayName("Several bean names given through name are recorded in order")
  void recordsSeveralBeanNames() {
    assertThat(
            onlyBean("@Bean(name = {\"a\", \"b\"}) DataSource ds() { return null; }").beanNames())
        .containsExactly("a", "b");
  }

  @Test
  @DisplayName("A bean name that is not a literal is kept as its raw expression")
  void keepsRawBeanNameExpression() {
    assertThat(onlyBean("@Bean(name = NAME) DataSource ds() { return null; }").beanNames())
        .containsExactly("NAME");
  }

  @Test
  @DisplayName("Factory method parameters are kept with their types")
  void keepsFactoryMethodParameters() {
    BeanDefinitionModel bean =
        onlyBean("@Bean DataSource ds(java.util.Properties props) { return null; }");

    assertThat(bean.parameters()).extracting(ParameterModel::name).containsExactly("props");
    assertThat(bean.parameters().get(0).type().text()).isEqualTo("java.util.Properties");
  }

  @Test
  @DisplayName("A method without @Bean is not listed")
  void ignoresMethodsWithoutBean() {
    ProjectModel model =
        analyze(
            """
            package com.example.config;
            import org.springframework.context.annotation.Configuration;
            @Configuration public class DataConfig {
              public String helper() { return ""; }
            }
            """);

    assertThat(model.beanDefinitions()).isEmpty();
  }

  @Test
  @DisplayName("An unrelated annotation also named Bean is not treated as a Spring @Bean")
  void ignoresForeignBeanAnnotation() {
    ProjectModel model =
        analyze(
            """
            package com.example.config;
            import com.other.Bean;
            public class Unrelated {
              @Bean public String helper() { return ""; }
            }
            """);

    assertThat(model.beanDefinitions()).isEmpty();
  }

  @Test
  @DisplayName("@Bean is found in any class and through an on-demand import")
  void findsBeanOutsideConfigurationClasses() {
    ProjectModel model =
        analyze(
            """
            package com.example;
            import org.springframework.boot.autoconfigure.SpringBootApplication;
            import org.springframework.context.annotation.*;
            @SpringBootApplication public class App {
              @Bean public String greeting() { return "hi"; }
            }
            """);

    assertThat(model.beanDefinitions())
        .extracting(BeanDefinitionModel::declaringType, BeanDefinitionModel::methodName)
        .containsExactly(org.assertj.core.api.Assertions.tuple("com.example.App", "greeting"));
  }

  @Test
  @DisplayName("A static @Bean method is listed")
  void findsStaticBeanMethod() {
    assertThat(onlyBean("@Bean static DataSource ds() { return null; }").methodName())
        .isEqualTo("ds");
  }

  private ConfigurationPropertiesModel onlyProperties(String annotation) {
    ProjectModel model =
        analyze(
            """
            package com.example.config;
            import org.springframework.boot.context.properties.ConfigurationProperties;
            %s
            public class MailProperties {}
            """
                .formatted(annotation));
    assertThat(model.configurationProperties()).hasSize(1);
    return model.configurationProperties().get(0);
  }

  @Test
  @DisplayName("@ConfigurationProperties on a class records the prefix attribute")
  void readsPrefixAttribute() {
    ConfigurationPropertiesModel properties =
        onlyProperties("@ConfigurationProperties(prefix = \"mail\")");

    assertThat(properties.typeName()).isEqualTo("com.example.config.MailProperties");
    assertThat(properties.beanMethodName()).isNull();
    assertThat(properties.prefix()).isEqualTo("mail");
    assertThat(properties.prefixResolved()).isTrue();
  }

  @Test
  @DisplayName("@ConfigurationProperties records the single-member form")
  void readsSingleMemberPrefix() {
    assertThat(onlyProperties("@ConfigurationProperties(\"mail\")").prefix()).isEqualTo("mail");
  }

  @Test
  @DisplayName("@ConfigurationProperties records the value attribute")
  void readsValueAttribute() {
    assertThat(onlyProperties("@ConfigurationProperties(value = \"mail\")").prefix())
        .isEqualTo("mail");
  }

  @Test
  @DisplayName("A constant prefix is kept as its raw expression and marked unresolved")
  void keepsConstantPrefixRaw() {
    ConfigurationPropertiesModel properties =
        onlyProperties("@ConfigurationProperties(prefix = Keys.MAIL)");

    assertThat(properties.prefix()).isEqualTo("Keys.MAIL");
    assertThat(properties.prefixResolved()).isFalse();
  }

  @Test
  @DisplayName("A missing prefix is null and not guessed")
  void missingPrefixIsNull() {
    ConfigurationPropertiesModel properties = onlyProperties("@ConfigurationProperties");

    assertThat(properties.prefix()).isNull();
    assertThat(properties.prefixResolved()).isTrue();
  }

  @Test
  @DisplayName("@ConfigurationProperties on a @Bean method records the method name")
  void readsPropertiesOnBeanMethod() {
    ProjectModel model =
        analyze(
            """
            package com.example.config;
            import javax.sql.DataSource;
            import org.springframework.boot.context.properties.ConfigurationProperties;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            @Configuration public class DataConfig {
              @Bean @ConfigurationProperties("app.datasource")
              public DataSource dataSource() { return null; }
            }
            """);

    assertThat(model.beanDefinitions()).hasSize(1);
    assertThat(model.configurationProperties()).hasSize(1);
    ConfigurationPropertiesModel properties = model.configurationProperties().get(0);
    assertThat(properties.typeName()).isEqualTo("com.example.config.DataConfig");
    assertThat(properties.beanMethodName()).isEqualTo("dataSource");
    assertThat(properties.prefix()).isEqualTo("app.datasource");
  }

  @Test
  @DisplayName("A project without configuration has empty lists")
  void noConfigurationMeansEmptyLists() {
    ProjectModel model = analyze("package com.example; public class Plain {}");

    assertThat(model.beanDefinitions()).isEmpty();
    assertThat(model.configurationProperties()).isEmpty();
  }
}
