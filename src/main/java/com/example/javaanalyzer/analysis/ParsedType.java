package com.example.javaanalyzer.analysis;

import com.github.javaparser.ast.body.TypeDeclaration;

/**
 * A type declaration together with what plugins need to interpret it. Only the analysis layer sees
 * the AST; the generation layer only sees the IR.
 *
 * @param sourceFile path relative to the project root, with forward slashes
 */
public record ParsedType(
    String qualifiedName,
    TypeDeclaration<?> declaration,
    String sourceFile,
    TypeNameResolver resolver) {}
