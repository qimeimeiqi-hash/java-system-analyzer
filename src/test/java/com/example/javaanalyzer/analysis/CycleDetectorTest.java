package com.example.javaanalyzer.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CycleDetectorTest {

  @Test
  @DisplayName("An acyclic graph has no cycles")
  void acyclicGraphHasNoCycles() {
    Map<String, Set<String>> graph = Map.of("a", Set.of("b", "c"), "b", Set.of("c"));

    assertThat(CycleDetector.findCycles(graph)).isEmpty();
  }

  @Test
  @DisplayName("An empty graph has no cycles")
  void emptyGraphHasNoCycles() {
    assertThat(CycleDetector.findCycles(Map.of())).isEmpty();
  }

  @Test
  @DisplayName("Two mutually dependent nodes form one cycle")
  void detectsTwoNodeCycle() {
    Map<String, Set<String>> graph = Map.of("b", Set.of("a"), "a", Set.of("b"));

    assertThat(CycleDetector.findCycles(graph)).containsExactly(List.of("a", "b"));
  }

  @Test
  @DisplayName("A node depending on itself is a cycle of one")
  void detectsSelfLoop() {
    Map<String, Set<String>> graph = Map.of("a", Set.of("a", "b"));

    assertThat(CycleDetector.findCycles(graph)).containsExactly(List.of("a"));
  }

  @Test
  @DisplayName("Nodes outside the cycle are not reported, even when they reach it")
  void excludesNodesOnlyReachingTheCycle() {
    Map<String, Set<String>> graph =
        Map.of(
            "entry", Set.of("x"),
            "x", Set.of("y"),
            "y", Set.of("z", "x"),
            "z", Set.of("leaf"));

    assertThat(CycleDetector.findCycles(graph)).containsExactly(List.of("x", "y"));
  }

  @Test
  @DisplayName("Separate cycles are reported separately, in a stable sorted order")
  void reportsSeparateCyclesInStableOrder() {
    Map<String, Set<String>> graph =
        Map.of(
            "q", Set.of("p"),
            "p", Set.of("q", "b"),
            "b", Set.of("a"),
            "a", Set.of("b"));

    assertThat(CycleDetector.findCycles(graph))
        .containsExactly(List.of("a", "b"), List.of("p", "q"));
  }

  @Test
  @DisplayName("Overlapping loops collapse into one strongly connected group")
  void mergesOverlappingLoops() {
    Map<String, Set<String>> graph =
        Map.of(
            "a", Set.of("b"),
            "b", Set.of("a", "c"),
            "c", Set.of("b"));

    assertThat(CycleDetector.findCycles(graph)).containsExactly(List.of("a", "b", "c"));
  }
}
