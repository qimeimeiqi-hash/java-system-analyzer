package com.example.javaanalyzer.analysis.plugin.view;

import com.example.javaanalyzer.ir.TemplateKind;
import com.example.javaanalyzer.ir.TemplateLinkModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the links, form targets and included parts written in one template (FR-17). The template is
 * not parsed as HTML or XML, because JSP is not well-formed and Thymeleaf templates need not be:
 * start tags are scanned and their attributes read as written. Nothing is evaluated; a value given
 * by an expression keeps its text and gets no path.
 *
 * <p>Comments are skipped, except Thymeleaf's parser-level blocks ({@code <!--/*}{@code /}), whose
 * content is processed by Thymeleaf. The bodies of {@code <script>} and {@code <style>} are
 * skipped.
 *
 * <p>Known limitations: links built in JavaScript, {@code <c:url var>} referenced later, and {@code
 * th:attr}/{@code data-th-*} forms are not resolved; {@code <%@ include %>} is read with a
 * double-quoted or single-quoted {@code file} only.
 */
final class TemplateScanner {

  /** An included part before its file is looked up. */
  record Include(String attribute, String value, String templateName, int line) {}

  record Result(List<TemplateLinkModel> links, List<Include> includes) {}

  private static final Pattern TAG_NAME = Pattern.compile("[A-Za-z][\\w:.-]*");
  private static final Pattern URL_TAG =
      Pattern.compile(
          "^<(?:c|spring):url\\b[^>]*?\\bvalue\\s*=\\s*(['\"])(.*?)\\1[^>]*>$", Pattern.DOTALL);
  private static final Pattern INCLUDE_DIRECTIVE =
      Pattern.compile("<%@\\s*include\\s+file\\s*=\\s*(['\"])(.*?)\\1\\s*%>");

  /** A link expression that is one literal, optionally with parameters: {@code '/a?b=1'}. */
  private static final Pattern QUOTED_LITERAL_URL =
      Pattern.compile("^'([^'$]*)'\\s*(?:\\(.*\\))?$", Pattern.DOTALL);

  private static final Pattern URL_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");
  private static final List<String> CONTEXT_PATH_PREFIXES =
      List.of(
          "${pageContext.request.contextPath}",
          "<%= request.getContextPath() %>",
          "<%=request.getContextPath()%>");
  private static final List<String> EXPRESSION_MARKERS =
      List.of("${", "*{", "#{", "<%", "'", "|", "+");
  private static final List<String> THYMELEAF_INCLUDES =
      List.of(
          "th:replace",
          "th:insert",
          "th:include",
          "th:substituteby",
          "layout:decorate",
          "layout:decorator");
  private static final String GET = "GET";
  private static final String POST = "POST";

  private final String text;
  private final TemplateKind kind;
  private final int[] lineStarts;
  private final List<TemplateLinkModel> links = new ArrayList<>();
  private final List<Include> includes = new ArrayList<>();

  private TemplateScanner(String text, TemplateKind kind) {
    this.text = masked(text);
    this.kind = kind;
    this.lineStarts = lineStarts(text);
  }

  static Result scan(String text, TemplateKind kind) {
    TemplateScanner scanner = new TemplateScanner(text, kind);
    scanner.scan();
    return new Result(List.copyOf(scanner.links), List.copyOf(scanner.includes));
  }

  private void scan() {
    if (kind == TemplateKind.JSP) {
      Matcher directive = INCLUDE_DIRECTIVE.matcher(text);
      while (directive.find()) {
        String file = directive.group(2);
        includes.add(new Include("include", file, jspTarget(file), lineOf(directive.start())));
      }
    }
    int index = text.indexOf('<');
    while (index >= 0) {
      int end = readTag(index);
      index = text.indexOf('<', Math.max(end, index + 1));
    }
  }

  /** Reads the start tag at {@code start}, if any; returns the index after it. */
  private int readTag(int start) {
    Matcher name = TAG_NAME.matcher(text).region(start + 1, text.length());
    if (!name.lookingAt()) {
      return start + 1;
    }
    Map<String, String> attributes = new LinkedHashMap<>();
    int i = name.end();
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '>') {
        handleTag(name.group().toLowerCase(Locale.ROOT), attributes, start);
        return i + 1;
      }
      if (Character.isWhitespace(c) || c == '/') {
        i++;
        continue;
      }
      int nameStart = i;
      while (i < text.length() && !isAttributeNameEnd(text.charAt(i))) {
        i++;
      }
      String attributeName = text.substring(nameStart, i).toLowerCase(Locale.ROOT);
      int afterName = skipWhitespace(i);
      String value = "";
      if (afterName < text.length() && text.charAt(afterName) == '=') {
        int valueStart = skipWhitespace(afterName + 1);
        if (valueStart >= text.length()) {
          return text.length();
        }
        char quote = text.charAt(valueStart);
        if (quote == '"' || quote == '\'') {
          int close = text.indexOf(quote, nestedTagEnd(valueStart + 1));
          if (close < 0) {
            return text.length();
          }
          value = text.substring(valueStart + 1, close);
          i = close + 1;
        } else {
          int valueEnd = valueStart;
          while (valueEnd < text.length()
              && !Character.isWhitespace(text.charAt(valueEnd))
              && text.charAt(valueEnd) != '>') {
            valueEnd++;
          }
          value = text.substring(valueStart, valueEnd);
          i = valueEnd;
        }
      }
      if (!attributeName.isEmpty()) {
        attributes.putIfAbsent(attributeName, value);
      }
      if (i == nameStart) {
        i++;
      }
    }
    return text.length();
  }

  /**
   * Where to look for the closing quote of a value starting at {@code start}. A JSP tag in a value
   * may use the same quotes as the attribute ({@code href="<spring:url value="/x"/>"}), because the
   * tag is processed before the HTML is written, so the quote is looked for after the tag.
   */
  private int nestedTagEnd(int start) {
    if (kind != TemplateKind.JSP || start >= text.length() || text.charAt(start) != '<') {
      return start;
    }
    char inQuote = 0;
    for (int i = start + 1; i < text.length(); i++) {
      char c = text.charAt(i);
      if (inQuote != 0) {
        inQuote = c == inQuote ? 0 : inQuote;
      } else if (c == '"' || c == '\'') {
        inQuote = c;
      } else if (c == '>') {
        return i + 1;
      }
    }
    return start;
  }

  private static boolean isAttributeNameEnd(char c) {
    return Character.isWhitespace(c) || c == '=' || c == '>' || c == '/';
  }

  private int skipWhitespace(int index) {
    int i = index;
    while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
      i++;
    }
    return i;
  }

  private void handleTag(String tagName, Map<String, String> attributes, int offset) {
    int line = lineOf(offset);
    switch (tagName) {
      case "a" -> addLink(tagName, attributes, line, "href", GET);
      case "form" -> addLink(tagName, attributes, line, "action", GET);
      case "form:form" -> {
        if (attributes.containsKey("servletrelativeaction")) {
          addLink(tagName, attributes, line, "servletrelativeaction", POST);
        } else {
          addLink(tagName, attributes, line, "action", POST);
        }
      }
      case "jsp:include" -> addJspInclude(tagName, attributes.get("page"), line);
      case "c:import" -> addJspInclude(tagName, attributes.get("url"), line);
      default -> {}
    }
    if (kind == TemplateKind.THYMELEAF) {
      for (String attribute : THYMELEAF_INCLUDES) {
        String value = attributes.get(attribute);
        if (value != null) {
          String template = fragmentTemplate(value);
          if (!template.isEmpty() && !template.equals("this")) {
            includes.add(new Include(attribute, value, thymeleafTemplateName(template), line));
          }
        }
      }
    }
  }

  private void addLink(
      String tagName,
      Map<String, String> attributes,
      int line,
      String attribute,
      String defaultMethod) {
    String usedAttribute = attribute;
    String value = attributes.get(attribute);
    String thymeleafAttribute = "th:" + attribute;
    if (kind == TemplateKind.THYMELEAF && attributes.containsKey(thymeleafAttribute)) {
      usedAttribute = thymeleafAttribute;
      value = attributes.get(thymeleafAttribute);
    }
    if (value == null || value.isBlank()) {
      return;
    }
    boolean thymeleaf = usedAttribute.startsWith("th:");
    String url = thymeleaf ? thymeleafUrl(value.strip()) : plainUrl(value.strip());
    if (url.startsWith("#") || url.startsWith("//") || URL_SCHEME.matcher(url).find()) {
      return; // an external URL or an anchor: not a screen transition
    }
    String httpMethod = GET;
    if (!tagName.equals("a")) {
      String method =
          kind == TemplateKind.THYMELEAF && attributes.containsKey("th:method")
              ? attributes.get("th:method")
              : attributes.get("method");
      httpMethod =
          method == null || method.isBlank()
              ? defaultMethod
              : method.strip().toUpperCase(Locale.ROOT);
    }
    links.add(
        new TemplateLinkModel(
            tagName, usedAttribute, value, literalPath(url), httpMethod, line, List.of()));
  }

  /**
   * The URL of a link expression {@code @{/owners/{id}(id=${o.id})}} without its parameters; the
   * value itself when it is not a link expression.
   */
  private static String thymeleafUrl(String value) {
    if (!value.startsWith("@{") || !value.endsWith("}")) {
      return value;
    }
    String inner = value.substring(2, value.length() - 1).strip();
    Matcher literal = QUOTED_LITERAL_URL.matcher(inner);
    if (literal.matches() && !literal.group(1).contains("__")) {
      return literal.group(1);
    }
    int parameters = inner.indexOf('(');
    return parameters < 0 ? inner : inner.substring(0, parameters).strip();
  }

  /** The URL of a plain attribute, read from {@code <c:url>} and without the context path. */
  private static String plainUrl(String value) {
    String url = value;
    Matcher urlTag = URL_TAG.matcher(url);
    if (urlTag.matches()) {
      url = urlTag.group(2).strip();
    }
    for (String prefix : CONTEXT_PATH_PREFIXES) {
      if (url.startsWith(prefix)) {
        String rest = url.substring(prefix.length());
        return rest.isEmpty() ? "/" : rest;
      }
    }
    return url;
  }

  /** The URL when it is a literal application path; null for an expression or a relative path. */
  private static String literalPath(String url) {
    return url.startsWith("/") && EXPRESSION_MARKERS.stream().noneMatch(url::contains) ? url : null;
  }

  /** The template part of a fragment expression such as {@code ~{fragments/layout :: head}}. */
  private static String fragmentTemplate(String value) {
    String expression = value.strip();
    if (expression.startsWith("~{") && expression.endsWith("}")) {
      expression = expression.substring(2, expression.length() - 1).strip();
    }
    int separator = expression.indexOf("::");
    return (separator < 0 ? expression : expression.substring(0, separator)).strip();
  }

  /** The template name, without a leading slash; null for an expression. */
  private static String thymeleafTemplateName(String template) {
    if (EXPRESSION_MARKERS.stream().anyMatch(template::contains)
        || template.contains("~{")
        || template.contains("(")) {
      return null;
    }
    return template.startsWith("/") ? template.substring(1) : template;
  }

  private void addJspInclude(String tagName, String value, int line) {
    if (value != null && !value.isBlank()) {
      includes.add(new Include(tagName, value, jspTarget(value.strip()), line));
    }
  }

  private static String jspTarget(String value) {
    return EXPRESSION_MARKERS.stream().anyMatch(value::contains) || URL_SCHEME.matcher(value).find()
        ? null
        : value;
  }

  private int lineOf(int offset) {
    int index = Arrays.binarySearch(lineStarts, offset);
    return (index >= 0 ? index : -index - 2) + 1;
  }

  private static int[] lineStarts(String text) {
    List<Integer> starts = new ArrayList<>(List.of(0));
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        starts.add(i + 1);
      }
    }
    return starts.stream().mapToInt(Integer::intValue).toArray();
  }

  /** Replaces skipped regions with spaces, keeping line breaks so offsets and lines still match. */
  static String masked(String text) {
    StringBuilder result = new StringBuilder(text);
    maskRegions(result, "<%--", "--%>", null);
    maskRegions(result, "<!--", "-->", "<!--/*/");
    maskBodies(result, "script");
    maskBodies(result, "style");
    return result.toString();
  }

  private static void maskRegions(StringBuilder text, String open, String close, String keep) {
    int start = text.indexOf(open);
    while (start >= 0) {
      if (keep != null && text.indexOf(keep, start) == start) {
        start = text.indexOf(open, start + open.length());
        continue;
      }
      int end = text.indexOf(close, start + open.length());
      int stop = end < 0 ? text.length() : end + close.length();
      blank(text, start, stop);
      start = text.indexOf(open, stop);
    }
  }

  private static void maskBodies(StringBuilder text, String tagName) {
    String lower = text.toString().toLowerCase(Locale.ROOT);
    int start = lower.indexOf("<" + tagName);
    while (start >= 0) {
      int bodyStart = lower.indexOf('>', start);
      if (bodyStart < 0) {
        return;
      }
      int end = lower.indexOf("</" + tagName, bodyStart);
      int stop = end < 0 ? text.length() : end;
      blank(text, bodyStart + 1, stop);
      start = end < 0 ? -1 : lower.indexOf("<" + tagName, end + 2);
    }
  }

  private static void blank(StringBuilder text, int start, int end) {
    for (int i = start; i < end; i++) {
      if (text.charAt(i) != '\n' && text.charAt(i) != '\r') {
        text.setCharAt(i, ' ');
      }
    }
  }
}
