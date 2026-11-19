package com.example.javaanalyzer.generation;

/**
 * Facts of the pre-analysis inspection (FR-02) that documents show next to the IR. They are not
 * part of the IR, so the caller passes them in; the generation layer still reads no source.
 *
 * @param springBootVersion null if unknown
 * @param pomPath null if not found
 */
public record ProjectFacts(String springBootVersion, String pomPath, String sourceEncoding) {}
