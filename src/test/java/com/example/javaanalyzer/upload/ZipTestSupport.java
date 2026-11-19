package com.example.javaanalyzer.upload;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds zip archives for tests. Entry names ending with "/" are written as directories. */
final class ZipTestSupport {

  private ZipTestSupport() {}

  static Path writeZip(Path zipFile, Map<String, String> entries) throws IOException {
    return writeZip(zipFile, entries, StandardCharsets.UTF_8);
  }

  static Path writeZip(Path zipFile, Map<String, String> entries, Charset entryNameCharset)
      throws IOException {
    try (OutputStream out = Files.newOutputStream(zipFile)) {
      writeZip(out, entries, entryNameCharset);
    }
    return zipFile;
  }

  static byte[] zipBytes(Map<String, String> entries) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeZip(out, entries, StandardCharsets.UTF_8);
    return out.toByteArray();
  }

  private static void writeZip(OutputStream out, Map<String, String> entries, Charset charset)
      throws IOException {
    try (ZipOutputStream zip = new ZipOutputStream(out, charset)) {
      for (Map.Entry<String, String> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        if (!entry.getKey().endsWith("/")) {
          zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
        zip.closeEntry();
      }
    }
  }

  /** A minimal Spring Boot pom using spring-boot-starter-parent. */
  static String springBootParentPom(String bootVersion) {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-parent</artifactId>
            <version>%s</version>
          </parent>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
        </project>
        """
        .formatted(bootVersion);
  }
}
