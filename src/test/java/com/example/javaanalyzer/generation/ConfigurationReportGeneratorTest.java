package com.example.javaanalyzer.generation;

import static com.example.javaanalyzer.generation.IrFixtures.component;
import static com.example.javaanalyzer.generation.IrFixtures.project;
import static com.example.javaanalyzer.generation.IrFixtures.type;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.javaanalyzer.ir.BeanDefinitionModel;
import com.example.javaanalyzer.ir.ComponentRole;
import com.example.javaanalyzer.ir.ConfigurationPropertiesModel;
import com.example.javaanalyzer.ir.DependencyModel;
import com.example.javaanalyzer.ir.DependencyResolution;
import com.example.javaanalyzer.ir.InjectionKind;
import com.example.javaanalyzer.ir.ParameterModel;
import com.example.javaanalyzer.ir.ProjectModel;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfigurationReportGeneratorTest {

  private static final String MAIL_CONFIG = "com.example.mail.config.MailConfig";
  private static final String DATA_CONFIG = "com.example.mail.config.DataConfig";

  /** spring-config-demo. */
  private static ProjectModel mail() {
    return project()
        .components(
            component(MAIL_CONFIG, ComponentRole.CONFIGURATION),
            component(DATA_CONFIG, ComponentRole.CONFIGURATION),
            component("com.example.mail.service.BroadcastService", ComponentRole.SERVICE))
        .beanDefinitions(
            new BeanDefinitionModel(
                MAIL_CONFIG,
                "emailNotifier",
                List.of(),
                type("com.example.mail.service.Notifier"),
                List.of(
                    new ParameterModel(
                        "properties", type("com.example.mail.config.MailProperties"), List.of()))),
            new BeanDefinitionModel(
                DATA_CONFIG,
                "dataSource",
                List.of("primaryDataSource", "mainDs"),
                type("javax.sql.DataSource"),
                List.of()))
        .configurationProperties(
            new ConfigurationPropertiesModel(
                "com.example.mail.config.MailProperties", null, "app.mail", true),
            new ConfigurationPropertiesModel(
                "com.example.mail.config.PoolProperties", "pool", "Prefixes.POOL", false))
        .dependencies(
            new DependencyModel(
                MAIL_CONFIG,
                "auditLogger",
                InjectionKind.CONSTRUCTOR,
                type("com.example.mail.config.AuditLogger"),
                "com.example.mail.config.AuditLogger",
                DependencyResolution.PROJECT_TYPE),
            new DependencyModel(
                MAIL_CONFIG,
                "environment",
                InjectionKind.FIELD,
                type("org.springframework.core.env.Environment"),
                null,
                DependencyResolution.EXTERNAL))
        .build();
  }

  @Test
  @DisplayName(
      "@Bean definitions show the declaring class, method, names, return type and arguments")
  void beanTable() {
    Table table = ConfigurationReportGenerator.beans(mail());

    assertThat(table.headers()).containsExactly("定義元クラス", "Bean メソッド", "Bean 名", "戻り値型", "引数");
    assertThat(table.texts())
        .containsExactly(
            List.of("DataConfig", "dataSource", "primaryDataSource, mainDs", "DataSource", "-"),
            List.of("MailConfig", "emailNotifier", "未指定", "Notifier", "MailProperties properties"));
  }

  @Test
  @DisplayName("@ConfigurationProperties show the prefix; an unresolved prefix is noted")
  void propertiesTable() {
    assertThat(ConfigurationReportGenerator.properties(mail()).texts())
        .containsExactly(
            List.of("MailProperties", "-", "app.mail"),
            List.of("PoolProperties", "pool", "Prefixes.POOL（未解決）"));
  }

  @Test
  @DisplayName("Configuration classes list their dependencies, unresolved ones with the reason")
  void configurationClasses() {
    assertThat(ConfigurationReportGenerator.configurationClasses(mail()).texts())
        .containsExactly(
            List.of("DataConfig", "-"), List.of("MailConfig", "AuditLogger, Environment（外部ライブラリ）"));
  }
}
