package com.example.javaanalyzer.ir;

/**
 * A problem found during analysis (FR-14).
 *
 * @param file path relative to the project root, or null when not tied to a file
 * @param line 1-based line, or null when unknown
 */
public record AnalysisIssue(
    IssueKind kind, IssueSeverity severity, String file, Integer line, String message) {}
