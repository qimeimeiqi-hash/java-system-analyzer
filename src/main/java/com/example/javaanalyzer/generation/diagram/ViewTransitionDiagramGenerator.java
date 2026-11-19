package com.example.javaanalyzer.generation.diagram;

import com.example.javaanalyzer.generation.Diagram;
import com.example.javaanalyzer.generation.ScreenTransitions;
import com.example.javaanalyzer.generation.ScreenTransitions.Transition;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.ir.ViewTemplateModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The screen transition diagram (FR-17, SCR-10): templates are nodes and an edge is a transition
 * found by {@link ScreenTransitions}. Transitions through a redirect or forward are dashed.
 * Includes are not transitions and links written as expressions give no edge.
 */
public final class ViewTransitionDiagramGenerator {

  private ViewTransitionDiagramGenerator() {}

  /** Empty when the project has no templates. */
  public static Optional<Diagram> diagram(ProjectModel model) {
    List<ViewTemplateModel> views = ScreenTransitions.sortedViews(model);
    if (views.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Integer> ids = new HashMap<>();
    StringBuilder code = new StringBuilder("flowchart LR\n");
    for (ViewTemplateModel view : views) {
      ids.put(view.path(), ids.size() + 1);
      code.append("  v")
          .append(ids.size())
          .append('[')
          .append(MermaidText.quoted(view.viewName() != null ? view.viewName() : view.path()))
          .append("]\n");
    }
    Graph graph = new Graph();
    for (Transition transition : ScreenTransitions.of(model)) {
      if (transition.link().path() == null) {
        continue;
      }
      String from = transition.from().path();
      transition.viewScreens().forEach(to -> graph.addEdge(from, to, false));
      transition.redirectScreens().forEach(to -> graph.addEdge(from, to, true));
    }
    List<int[]> edges = new ArrayList<>();
    Map<String, Boolean> dashed = new HashMap<>();
    graph
        .edges()
        .forEach(
            (from, targets) ->
                targets.forEach(
                    (to, isDashed) -> {
                      Integer fromId = ids.get(from);
                      Integer toId = ids.get(to);
                      if (fromId != null && toId != null) {
                        edges.add(new int[] {fromId, toId});
                        dashed.put(fromId + ">" + toId, isDashed);
                      }
                    }));
    edges.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
    for (int[] edge : edges) {
      code.append("  v")
          .append(edge[0])
          .append(dashed.get(edge[0] + ">" + edge[1]) ? " -.-> v" : " --> v")
          .append(edge[1])
          .append('\n');
    }
    return Optional.of(new Diagram("screens", "画面遷移", code.toString()));
  }
}
