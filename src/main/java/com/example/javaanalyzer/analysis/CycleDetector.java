package com.example.javaanalyzer.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds cycles in a directed graph using Tarjan's strongly connected components algorithm. */
final class CycleDetector {

  private final Map<String, Set<String>> graph;
  private final Map<String, Integer> visitOrder = new HashMap<>();
  private final Map<String, Integer> lowestReachable = new HashMap<>();
  private final Deque<String> stack = new ArrayDeque<>();
  private final Set<String> onStack = new HashSet<>();
  private final List<List<String>> cycles = new ArrayList<>();

  private CycleDetector(Map<String, Set<String>> graph) {
    this.graph = graph;
  }

  /**
   * Returns each group of mutually reachable nodes, including a node that points to itself.
   *
   * @param graph node to the nodes it points to; targets need not be keys
   * @return groups with sorted members, sorted by their first member
   */
  static List<List<String>> findCycles(Map<String, Set<String>> graph) {
    CycleDetector detector = new CycleDetector(graph);
    for (String node : graph.keySet()) {
      if (!detector.visitOrder.containsKey(node)) {
        detector.visit(node);
      }
    }
    detector.cycles.sort(Comparator.comparing(cycle -> cycle.get(0)));
    return List.copyOf(detector.cycles);
  }

  private void visit(String node) {
    int order = visitOrder.size();
    visitOrder.put(node, order);
    lowestReachable.put(node, order);
    stack.push(node);
    onStack.add(node);

    for (String target : graph.getOrDefault(node, Set.of())) {
      if (!visitOrder.containsKey(target)) {
        visit(target);
        lowestReachable.merge(node, lowestReachable.get(target), Math::min);
      } else if (onStack.contains(target)) {
        lowestReachable.merge(node, visitOrder.get(target), Math::min);
      }
    }

    if (lowestReachable.get(node).equals(visitOrder.get(node))) {
      List<String> group = popGroup(node);
      if (group.size() > 1 || graph.getOrDefault(node, Set.of()).contains(node)) {
        cycles.add(group.stream().sorted().toList());
      }
    }
  }

  private List<String> popGroup(String root) {
    List<String> group = new ArrayList<>();
    String member;
    do {
      member = stack.pop();
      onStack.remove(member);
      group.add(member);
    } while (!member.equals(root));
    return group;
  }
}
