// expect: none
package com.example.javaanalyzer.generation;

import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.io.IrReader;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

class Allowed {
  String sql = """
      import com.example.javaanalyzer.analysis.ParsedType;
      \"""still inside\"""
      """;
  char quote = '"';
  String afterQuote = "com.example.javaanalyzer.upload.X";
}
