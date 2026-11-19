// expect: R2:8
package com.example.javaanalyzer.generation.diagram;

import java.io.*;

class IoWildcard {
  ByteArrayOutputStream buffer = new ByteArrayOutputStream();
  Object source = new File("src/main/java");
  String profile = "File";
}
