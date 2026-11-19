package com.example.javaanalyzer.analysis.plugin.springboot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Qualified annotation names used by the Spring Boot plugin. */
final class AnnotationNames {

  private AnnotationNames() {}

  static final String LOMBOK = "lombok.";

  static final String CONTROLLER = "org.springframework.stereotype.Controller";
  static final String REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";
  static final String SERVICE = "org.springframework.stereotype.Service";
  static final String REPOSITORY = "org.springframework.stereotype.Repository";
  static final String COMPONENT = "org.springframework.stereotype.Component";

  static final String CONFIGURATION = "org.springframework.context.annotation.Configuration";
  static final String BEAN = "org.springframework.context.annotation.Bean";
  static final String CONFIGURATION_PROPERTIES =
      "org.springframework.boot.context.properties.ConfigurationProperties";

  static final String AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";
  static final String QUALIFIER = "org.springframework.beans.factory.annotation.Qualifier";

  static final String WEB_BIND = "org.springframework.web.bind.annotation.";
  static final String REQUEST_MAPPING = WEB_BIND + "RequestMapping";
  static final String REQUEST_BODY = WEB_BIND + "RequestBody";
  static final String RESPONSE_BODY = WEB_BIND + "ResponseBody";

  /** Both Spring Boot 3 ({@code jakarta.*}) and Spring Boot 2 ({@code javax.*}) names. */
  static String[] jakartaOrJavax(String packageSuffixAndName) {
    return new String[] {"jakarta." + packageSuffixAndName, "javax." + packageSuffixAndName};
  }

  /** JPA annotation, e.g. {@code persistence("Entity")}. */
  static String[] persistence(String simpleName) {
    return jakartaOrJavax("persistence." + simpleName);
  }

  static String lombok(String simpleName) {
    return LOMBOK + simpleName;
  }

  /**
   * Every annotation this plugin recognizes; used to qualify annotations imported on demand in the
   * IR (e.g. {@code import lombok.*;}).
   */
  static List<String> recognized() {
    List<String> names = new ArrayList<>();
    for (String simpleName :
        List.of(
            "Getter",
            "Setter",
            "Data",
            "Value",
            "Builder",
            "ToString",
            "EqualsAndHashCode",
            "NoArgsConstructor",
            "AllArgsConstructor",
            "RequiredArgsConstructor",
            "NonNull")) {
      names.add(lombok(simpleName));
    }
    for (String simpleName :
        List.of(
            "Entity",
            "Table",
            "Id",
            "GeneratedValue",
            "Column",
            "Transient",
            "Enumerated",
            "Embedded",
            "EmbeddedId",
            "Embeddable",
            "MappedSuperclass",
            "OneToOne",
            "OneToMany",
            "ManyToOne",
            "ManyToMany",
            "JoinColumn",
            "JoinColumns",
            "JoinTable",
            "Version",
            "Lob",
            "Temporal")) {
      names.addAll(Arrays.asList(persistence(simpleName)));
    }
    for (String simpleName :
        List.of(
            "RestController",
            "RequestMapping",
            "GetMapping",
            "PostMapping",
            "PutMapping",
            "DeleteMapping",
            "PatchMapping",
            "RequestBody",
            "ResponseBody",
            "PathVariable",
            "RequestParam",
            "RequestHeader",
            "ResponseStatus")) {
      names.add(WEB_BIND + simpleName);
    }
    names.addAll(List.of(CONTROLLER, SERVICE, REPOSITORY, COMPONENT, AUTOWIRED, QUALIFIER));
    names.addAll(List.of(CONFIGURATION, BEAN, CONFIGURATION_PROPERTIES));
    names.addAll(Arrays.asList(jakartaOrJavax("inject.Inject")));
    names.addAll(Arrays.asList(jakartaOrJavax("annotation.Resource")));
    return names;
  }
}
