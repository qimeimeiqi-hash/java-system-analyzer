package com.example.javaanalyzer.generation.diagram;

import com.example.javaanalyzer.generation.Diagram;

/**
 * Result of the component dependency diagram (FR-08).
 *
 * @param diagram null when there are no components or a package filter is required
 * @param nodeCount number of nodes before any package filter
 * @param filterRequired the project has more than {@link
 *     DependencyDiagramGenerator#MAX_UNFILTERED_NODES} nodes and no package was chosen
 */
public record ComponentDiagram(Diagram diagram, int nodeCount, boolean filterRequired) {}
