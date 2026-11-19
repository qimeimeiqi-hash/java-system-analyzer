package com.example.javaanalyzer.generation.export;

import com.example.javaanalyzer.generation.Diagram;
import com.example.javaanalyzer.generation.KeyValue;
import com.example.javaanalyzer.generation.Table;
import java.util.List;

/** One element of an {@link ExportDocument}; every renderer handles every kind. */
public sealed interface Block {

  /**
   * @param level 1 for a chapter, 2 to 4 below it
   */
  record Heading(int level, String text) implements Block {}

  /** The cover page of a formal document. */
  record Cover(String title, List<KeyValue> details, String remark) implements Block {
    public Cover {
      details = List.copyOf(details);
    }
  }

  /** The table of contents, built from the headings by the format that supports it. */
  record TableOfContents() implements Block {}

  record PageBreak() implements Block {}

  /** A numbered caption of a figure or table, e.g. "図 2-1 レイヤー構成". */
  record Caption(String text) implements Block {}

  record BulletList(List<String> items) implements Block {
    public BulletList {
      items = List.copyOf(items);
    }
  }

  record Paragraph(String text) implements Block {}

  /** A remark about how the content was obtained, e.g. what the analysis does not detect. */
  record Note(String text) implements Block {}

  /** A table; an empty one is shown as "検出されませんでした". */
  record TableBlock(Table table) implements Block {}

  record KeyValueBlock(List<KeyValue> keyValues) implements Block {
    public KeyValueBlock {
      keyValues = List.copyOf(keyValues);
    }
  }

  /** Text shown as written, such as SQL. */
  record Code(String text) implements Block {}

  /**
   * A diagram. Formats that cannot draw Mermaid embed the PNG made by the browser; when there is no
   * usable PNG they show the fallback table and the Mermaid code instead (SCR-11).
   *
   * @param fallback table shown in place of a missing image; null when none is meaningful
   */
  record DiagramBlock(Diagram diagram, Table fallback) implements Block {}
}
