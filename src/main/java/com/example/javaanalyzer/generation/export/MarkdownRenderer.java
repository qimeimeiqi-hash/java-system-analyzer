package com.example.javaanalyzer.generation.export;

import com.example.javaanalyzer.generation.Cell;
import com.example.javaanalyzer.generation.KeyValue;
import com.example.javaanalyzer.generation.Section;
import com.example.javaanalyzer.generation.Table;
import com.example.javaanalyzer.generation.export.Block.BulletList;
import com.example.javaanalyzer.generation.export.Block.Caption;
import com.example.javaanalyzer.generation.export.Block.Code;
import com.example.javaanalyzer.generation.export.Block.Cover;
import com.example.javaanalyzer.generation.export.Block.DiagramBlock;
import com.example.javaanalyzer.generation.export.Block.Heading;
import com.example.javaanalyzer.generation.export.Block.KeyValueBlock;
import com.example.javaanalyzer.generation.export.Block.Note;
import com.example.javaanalyzer.generation.export.Block.PageBreak;
import com.example.javaanalyzer.generation.export.Block.Paragraph;
import com.example.javaanalyzer.generation.export.Block.TableBlock;
import com.example.javaanalyzer.generation.export.Block.TableOfContents;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders an {@link ExportDocument} as GitHub-flavored Markdown (FR-11). Diagrams stay Mermaid code
 * blocks, which Markdown viewers such as GitHub draw themselves (SCR-11).
 */
public final class MarkdownRenderer {

  private static final Pattern BACKTICK_RUN = Pattern.compile("`+");

  private MarkdownRenderer() {}

  public static String render(ExportDocument document) {
    StringBuilder out = new StringBuilder("# ").append(document.title()).append("\n\n");
    for (Block block : document.blocks()) {
      switch (block) {
        case Heading heading ->
            out.append("#".repeat(heading.level() + 1))
                .append(' ')
                .append(text(heading.text()))
                .append("\n\n");
        case Paragraph paragraph -> out.append(text(paragraph.text())).append("\n\n");
        case Note note -> out.append("> ").append(oneLine(note.text())).append("\n\n");
        case TableBlock table -> appendTable(out, table.table());
        case KeyValueBlock keyValues -> appendTable(out, keyValueTable(keyValues.keyValues()));
        case Code code -> appendFence(out, "", code.text());
        case Cover cover -> {
          cover
              .details()
              .forEach(
                  detail ->
                      out.append(text(detail.key()))
                          .append("：")
                          .append(text(detail.value().text()))
                          .append("  \n"));
          out.append("\n> ").append(oneLine(cover.remark())).append("\n\n");
        }
        case TableOfContents toc -> {
          // Markdown viewers build their own outline from the headings
        }
        case PageBreak pageBreak -> {
          // No page concept in Markdown
        }
        case Caption caption -> out.append('*').append(text(caption.text())).append("*\n\n");
        case BulletList list -> {
          list.items().forEach(item -> out.append("- ").append(oneLine(item)).append('\n'));
          out.append('\n');
        }
        case DiagramBlock diagram -> {
          out.append("**").append(text(diagram.diagram().title())).append("**\n\n");
          appendFence(out, "mermaid", diagram.diagram().code());
        }
      }
    }
    return out.toString();
  }

  static Table keyValueTable(List<KeyValue> keyValues) {
    return new Table(
        List.of("項目", "内容"),
        keyValues.stream().map(kv -> List.of(Cell.of(kv.key()), kv.value())).toList());
  }

  private static void appendTable(StringBuilder out, Table table) {
    if (table.isEmpty()) {
      out.append(Section.NOT_DETECTED).append("\n\n");
      return;
    }
    out.append(row(table.headers().stream().map(MarkdownRenderer::cell).toList()));
    out.append(row(table.headers().stream().map(header -> "---").toList()));
    for (List<String> row : table.texts()) {
      out.append(row(row.stream().map(MarkdownRenderer::cell).toList()));
    }
    out.append('\n');
  }

  private static String row(List<String> cells) {
    return "| " + String.join(" | ", cells) + " |\n";
  }

  private static String cell(String text) {
    return text(text).replace("|", "\\|").replace("\r\n", "\n").replace("\n", "<br>");
  }

  private static String oneLine(String text) {
    return text(text).replace("\r\n", " ").replace('\n', ' ');
  }

  /**
   * Escapes the characters Markdown viewers read as HTML, so text such as {@code List<Foo>} from
   * the source stays visible. Code blocks are left as written.
   */
  private static String text(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /** A fence longer than any run of backticks in the text, so the text cannot close it. */
  private static void appendFence(StringBuilder out, String language, String text) {
    int longest = 0;
    Matcher matcher = BACKTICK_RUN.matcher(text);
    while (matcher.find()) {
      longest = Math.max(longest, matcher.group().length());
    }
    String fence = "`".repeat(Math.max(3, longest + 1));
    out.append(fence).append(language).append('\n').append(text);
    if (!text.endsWith("\n")) {
      out.append('\n');
    }
    out.append(fence).append("\n\n");
  }
}
