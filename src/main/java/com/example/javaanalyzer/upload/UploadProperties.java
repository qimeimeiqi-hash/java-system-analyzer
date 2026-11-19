package com.example.javaanalyzer.upload;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Upload settings bound from {@code analyzer.upload.*}.
 *
 * @param workspaceDir root directory under which each uploaded project gets its own folder
 * @param maxExtractedSize upper limit of the total uncompressed size (zip bomb protection)
 * @param maxEntryCount upper limit of the number of entries in one zip
 */
@ConfigurationProperties("analyzer.upload")
public record UploadProperties(
    @DefaultValue("workspace") String workspaceDir,
    @DefaultValue("200MB") DataSize maxExtractedSize,
    @DefaultValue("20000") int maxEntryCount) {}
