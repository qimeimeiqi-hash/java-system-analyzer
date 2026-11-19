// expect: R1:11 R1:13
package com.example.javaanalyzer.generation;

/**
 * Mentions {@link com.example.javaanalyzer.analysis.ParsedType} only in Javadoc: not a reference.
 */
class FullyQualifiedWeb {
  // com.example.javaanalyzer.upload.UploadService in a line comment: not a reference.
  String name = "com.example.javaanalyzer.analysis.ParsedType";

  com.example.javaanalyzer.web.ProjectController controller;

  Object node = new com.example.javaanalyzer
      .analysis.ParsedType();
}
