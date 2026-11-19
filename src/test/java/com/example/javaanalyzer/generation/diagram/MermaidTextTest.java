package com.example.javaanalyzer.generation.diagram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MermaidTextTest {

  @Test
  @DisplayName("Quoted labels escape quotes, generics, ampersands, hashes and line breaks")
  void quotedEscapesMarkup() {
    assertThat(MermaidText.quoted("Map<String, \"x\"> & #1\nnext"))
        .isEqualTo("\"Map#lt;String, #quot;x#quot;#gt; #amp; #35;1 next\"");
  }

  @Test
  @DisplayName("Japanese and dollar signs survive in labels")
  void quotedKeepsJapaneseAndDollar() {
    assertThat(MermaidText.quoted("注文$Inner")).isEqualTo("\"注文$Inner\"");
  }

  @Test
  @DisplayName(
      "Bare words keep ASCII letters and digits only (Mermaid ER rejects others) and replace the rest")
  void wordReplacesSymbols() {
    assertThat(MermaidText.word("List<Order>")).isEqualTo("List_Order_");
    assertThat(MermaidText.word("byte[]")).isEqualTo("byte__");
    assertThat(MermaidText.word("顧客名")).isEqualTo("___");
    assertThat(MermaidText.word("1st")).isEqualTo("_1st");
  }

  @Test
  @DisplayName("Tells whether a bare word lost characters that a reader needs (non-ASCII)")
  void detectsLostCharacters() {
    assertThat(MermaidText.losesCharacters("顧客名")).isTrue();
    assertThat(MermaidText.losesCharacters("List<Order>")).isFalse();
  }

  @Test
  @DisplayName(
      "Percent signs and braces are escaped so a label cannot carry a %%{init}%% directive")
  void quotedEscapesDirectiveCharacters() {
    assertThat(MermaidText.quoted("t%%{init: {'theme':'dark'}}%%"))
        .isEqualTo("\"t#37;#37;#123;init: #123;'theme':'dark'#125;#125;#37;#37;\"")
        .doesNotContain("%")
        .doesNotContain("{");
  }

  @Test
  @DisplayName(
      "Bare words that Mermaid ER reads as key markers (PK, FK, UK in any case) are prefixed")
  void wordAvoidsErKeyMarkers() {
    assertThat(MermaidText.word("pk")).isEqualTo("_pk");
    assertThat(MermaidText.word("FK")).isEqualTo("_FK");
    assertThat(MermaidText.word("Uk")).isEqualTo("_Uk");
    assertThat(MermaidText.word("pkey")).isEqualTo("pkey");
    assertThat(MermaidText.losesCharacters("pk")).isTrue();
    assertThat(MermaidText.losesCharacters("pkey")).isFalse();
  }
}
