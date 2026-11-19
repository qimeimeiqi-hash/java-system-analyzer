package com.example.mail.config;

import javax.sql.DataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** DataSource の定義。 */
@Configuration
public class DataConfig {

  @Bean("mainDataSource")
  @ConfigurationProperties("app.datasource")
  public DataSource dataSource() {
    return DataSourceBuilder.create().build();
  }
}
