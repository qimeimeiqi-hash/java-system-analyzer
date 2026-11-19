package com.example.javaanalyzer.ir;

/**
 * Refers to an {@link EndpointModel}. Overloaded handler methods share a reference.
 *
 * @param controllerType qualified name of the controller
 */
public record EndpointRef(String controllerType, String methodName) {}
