package com.example.javaanalyzer.ir;

/** A {@code @JoinColumn}; null values mean "not specified". */
public record JoinColumnModel(String name, String referencedColumnName, Boolean nullable) {}
