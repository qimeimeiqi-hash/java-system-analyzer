package com.example.javaanalyzer.upload;

/** Outcome of one upload. The project id identifies the workspace folder used by later analysis. */
public record UploadResult(String projectId, InspectionResult inspection) {}
