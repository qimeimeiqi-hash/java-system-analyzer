package com.example.javaanalyzer.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies only that the javaparser-core dependency works; not production logic. */
class JavaParserSmokeTest {

  @Test
  @DisplayName("Parsing 'class Foo {}' yields a class named Foo")
  void parsesInlineSourceAndReturnsClassName() {
    ParseResult<CompilationUnit> result = new JavaParser().parse("class Foo {}");

    assertThat(result.isSuccessful()).isTrue();
    assertThat(result.getResult().orElseThrow().findAll(ClassOrInterfaceDeclaration.class))
        .extracting(ClassOrInterfaceDeclaration::getNameAsString)
        .containsExactly("Foo");
  }

  @Test
  @DisplayName("Parsing syntactically invalid source reports problems instead of throwing")
  void reportsProblemsForInvalidSource() {
    ParseResult<CompilationUnit> result = new JavaParser().parse("class Foo {");

    assertThat(result.isSuccessful()).isFalse();
    assertThat(result.getProblems()).isNotEmpty();
  }
}
