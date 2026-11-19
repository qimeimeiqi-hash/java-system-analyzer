package com.example.javaanalyzer.web;

import com.example.javaanalyzer.ir.ProjectModel;

/**
 * A saved project as shown on the result screens.
 *
 * @param model the IR; null unless the analysis succeeded and the IR is of the current version
 * @param irOutdated the IR was saved by another schema version and the project must be re-uploaded
 */
public record ProjectResult(
    String projectId, SavedProjectResult saved, ProjectModel model, boolean irOutdated) {

  /** Whether the analysis screens (left navigation) can be shown. */
  public boolean analyzed() {
    return model != null;
  }
}
