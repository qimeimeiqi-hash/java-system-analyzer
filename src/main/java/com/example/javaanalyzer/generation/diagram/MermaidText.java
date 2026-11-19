package com.example.javaanalyzer.generation.diagram;

import java.util.Locale;
import java.util.Set;

/**
 * Makes names from the uploaded source safe inside Mermaid code (screen-design 2.5). Node ids are
 * always generated ({@code n1}, {@code e1}, ...), so source text only appears in labels.
 */
final class MermaidText {

  /** Words Mermaid's ER grammar reads as key markers in an attribute, in any letter case. */
  private static final Set<String> ER_KEY_MARKERS = Set.of("pk", "fk", "uk");

  private MermaidText() {}

  /**
   * A double-quoted label; characters that end the label or form markup become entity codes.
   * Percent signs and braces are escaped too: Mermaid looks for {@code %%{...}%%} directives
   * anywhere in the code, even inside quotes, and a directive from the uploaded source could
   * restyle the diagram.
   */
  static String quoted(String text) {
    StringBuilder escaped = new StringBuilder("\"");
    for (char c : text.toCharArray()) {
      switch (c) {
        case '"' -> escaped.append("#quot;");
        case '<' -> escaped.append("#lt;");
        case '>' -> escaped.append("#gt;");
        case '&' -> escaped.append("#amp;");
        case '#' -> escaped.append("#35;");
        case '%' -> escaped.append("#37;");
        case '{' -> escaped.append("#123;");
        case '}' -> escaped.append("#125;");
        case '\r', '\n', '\t' -> escaped.append(' ');
        default -> escaped.append(c);
      }
    }
    return escaped.append('"').toString();
  }

  /**
   * A bare word for places that do not accept quotes, such as ER attribute types and names. Mermaid
   * accepts ASCII letters, digits and underscores only there, so every other character, including
   * Japanese identifiers, becomes an underscore; see {@link #losesCharacters}. A word that Mermaid
   * would read as a key marker ({@code pk}, {@code fk}, {@code uk}) gets a leading underscore.
   */
  static String word(String text) {
    String replaced = text.replaceAll("[^A-Za-z0-9_]", "_");
    return replaced.isEmpty() || Character.isDigit(replaced.charAt(0)) || isErKeyMarker(replaced)
        ? "_" + replaced
        : replaced;
  }

  private static boolean isErKeyMarker(String word) {
    return ER_KEY_MARKERS.contains(word.toLowerCase(Locale.ROOT));
  }

  /**
   * Whether {@link #word} drops characters a reader needs, so the original must be shown elsewhere.
   * Symbols such as {@code <>} of generics stay readable as underscores; non-ASCII letters do not,
   * and a key marker word changed by {@link #word} is noted too.
   */
  static boolean losesCharacters(String text) {
    return text.chars().anyMatch(c -> c > 0x7F) || isErKeyMarker(text);
  }
}
