// expect: R2:4 R2:8
package com.example.javaanalyzer.generation.export;

import java.nio.file.Files;

class ReadsFiles {
  Object read() throws Exception {
    return new java.io.FileReader("pom.xml");
  }
}
