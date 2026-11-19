package com.example.javaanalyzer.ir;

/** Kind of analysis problem (FR-14). */
public enum IssueKind {
  SYNTAX_ERROR,
  UNRESOLVED_IMPORT,
  ENCODING_FALLBACK,
  XML_PARSE_ERROR,
  /** A setting needed to tell which template a view name renders is missing or unreadable. */
  VIEW_SETTING_UNRESOLVED,
  CIRCULAR_DEPENDENCY,
  /** The same qualified type name is declared in more than one source file. */
  DUPLICATE_TYPE
}
