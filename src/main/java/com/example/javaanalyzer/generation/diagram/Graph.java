package com.example.javaanalyzer.generation.diagram;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A small directed graph for the dependency diagrams. An edge is dashed only when every dependency
 * behind it is dashed, so one direct dependency makes it solid.
 */
final class Graph {

  private final Set<String> nodes = new TreeSet<>();
  private final Map<String, Map<String, Boolean>> edges = new TreeMap<>();

  Set<String> nodes() {
    return nodes;
  }

  /** Source node to (target node to dashed). */
  Map<String, Map<String, Boolean>> edges() {
    return edges;
  }

  void addEdge(String from, String to, boolean dashed) {
    nodes.add(from);
    nodes.add(to);
    edges.computeIfAbsent(from, unused -> new TreeMap<>()).merge(to, dashed, Boolean::logicalAnd);
  }

  /**
   * Nodes on a cycle: members of a strongly connected component with more than one node, or with an
   * edge to themselves (Tarjan's algorithm, iterative to avoid deep recursion).
   */
  Set<String> cycleNodes() {
    Map<String, Integer> index = new HashMap<>();
    Map<String, Integer> lowLink = new HashMap<>();
    Deque<String> stack = new ArrayDeque<>();
    Set<String> onStack = new HashSet<>();
    Set<String> result = new TreeSet<>();
    int[] counter = {0};
    for (String start : nodes) {
      if (index.containsKey(start)) {
        continue;
      }
      Deque<Frame> work = new ArrayDeque<>();
      work.push(new Frame(start, targets(start).iterator()));
      index.put(start, counter[0]);
      lowLink.put(start, counter[0]++);
      stack.push(start);
      onStack.add(start);
      while (!work.isEmpty()) {
        Frame frame = work.peek();
        if (frame.targets().hasNext()) {
          String next = frame.targets().next();
          if (!index.containsKey(next)) {
            index.put(next, counter[0]);
            lowLink.put(next, counter[0]++);
            stack.push(next);
            onStack.add(next);
            work.push(new Frame(next, targets(next).iterator()));
          } else if (onStack.contains(next)) {
            lowLink.put(frame.node(), Math.min(lowLink.get(frame.node()), index.get(next)));
          }
          continue;
        }
        work.pop();
        if (!work.isEmpty()) {
          String parent = work.peek().node();
          lowLink.put(parent, Math.min(lowLink.get(parent), lowLink.get(frame.node())));
        }
        if (lowLink.get(frame.node()).equals(index.get(frame.node()))) {
          Set<String> component = new HashSet<>();
          String member;
          do {
            member = stack.pop();
            onStack.remove(member);
            component.add(member);
          } while (!member.equals(frame.node()));
          if (component.size() > 1 || targets(frame.node()).contains(frame.node())) {
            result.addAll(component);
          }
        }
      }
    }
    return result;
  }

  private Set<String> targets(String node) {
    return edges.getOrDefault(node, Map.of()).keySet();
  }

  private record Frame(String node, java.util.Iterator<String> targets) {}
}
