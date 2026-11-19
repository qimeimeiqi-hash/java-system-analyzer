package com.example.javaanalyzer.generation.export;

import com.example.javaanalyzer.generation.Diagram;
import java.util.List;

/** A document independent of its file format: a title and blocks in reading order. */
public record ExportDocument(String title, List<Block> blocks) {

  public ExportDocument {
    blocks = List.copyOf(blocks);
  }

  /** The diagrams of the document, in order; the browser converts exactly these to PNG. */
  public List<Diagram> diagrams() {
    return blocks.stream()
        .filter(Block.DiagramBlock.class::isInstance)
        .map(block -> ((Block.DiagramBlock) block).diagram())
        .toList();
  }
}
