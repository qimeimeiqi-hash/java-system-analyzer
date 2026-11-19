package com.example.javaanalyzer.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.javaanalyzer.ir.AnalysisIssue;
import com.example.javaanalyzer.ir.IssueKind;
import com.example.javaanalyzer.ir.IssueSeverity;
import com.example.javaanalyzer.ir.ProjectModel;
import com.example.javaanalyzer.upload.InspectionIssue;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProjectController.class)
class ProjectControllerTest {

  private static final String ID = "3f2b8c1e-7a4d-4f60-9b21-0c5de8a1f7aa";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ProjectAnalysisService analysisService;

  private static SavedProjectResult saved(AnalysisStatus status, List<InspectionIssue> issues) {
    return new SavedProjectResult("demo/pom.xml", "3.2.5", "UTF-8", issues, status);
  }

  private static ProjectModel model(List<AnalysisIssue> issues) {
    return new ProjectModel(
        "com.example",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        issues);
  }

  @Test
  @DisplayName("SCR-02 of an analyzed project shows the inspection, navigation and IR link")
  void showsAnalyzedProject() throws Exception {
    AnalysisIssue warning =
        new AnalysisIssue(IssueKind.CIRCULAR_DEPENDENCY, IssueSeverity.WARNING, null, null, "x");
    given(analysisService.load(ID))
        .willReturn(
            Optional.of(
                new ProjectResult(
                    ID,
                    saved(AnalysisStatus.SUCCEEDED, List.of()),
                    model(List.of(warning)),
                    false)));

    mockMvc
        .perform(get("/projects/" + ID))
        .andExpect(status().isOk())
        .andExpect(view().name("project/overview"))
        .andExpect(content().string(containsString("対応可能")))
        .andExpect(content().string(containsString("demo/pom.xml")))
        .andExpect(content().string(containsString("3.2.5")))
        .andExpect(content().string(containsString("com.example")))
        .andExpect(content().string(containsString("エラー 0 件 / 警告 1 件")))
        .andExpect(content().string(containsString("/projects/" + ID + "/apis")))
        .andExpect(content().string(containsString("/projects/" + ID + "/ir/project.json")));
  }

  @Test
  @DisplayName("Syntax errors found by FR-14 are shown as counts with 200, not as a page failure")
  void syntaxErrorsAreShownWith200() throws Exception {
    AnalysisIssue syntaxError =
        new AnalysisIssue(
            IssueKind.SYNTAX_ERROR, IssueSeverity.ERROR, "src/main/java/A.java", 3, "構文エラー");
    given(analysisService.load(ID))
        .willReturn(
            Optional.of(
                new ProjectResult(
                    ID,
                    saved(AnalysisStatus.SUCCEEDED, List.of()),
                    model(List.of(syntaxError)),
                    false)));

    mockMvc
        .perform(get("/projects/" + ID))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("エラー 1 件 / 警告 0 件")))
        .andExpect(content().string(not(containsString("解析に失敗しました"))));
  }

  @Test
  @DisplayName("SCR-02 of an unsupported project shows only the inspection, without navigation")
  void showsUnsupportedProjectWithoutNavigation() throws Exception {
    given(analysisService.load(ID))
        .willReturn(
            Optional.of(
                new ProjectResult(
                    ID,
                    saved(
                        AnalysisStatus.UNSUPPORTED,
                        List.of(InspectionIssue.error("pom.xml が見つかりません。"))),
                    null,
                    false)));

    mockMvc
        .perform(get("/projects/" + ID))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("対応不可")))
        .andExpect(content().string(containsString("pom.xml が見つかりません。")))
        .andExpect(content().string(not(containsString("/apis"))))
        .andExpect(content().string(not(containsString("/ir/project.json"))));
  }

  @Test
  @DisplayName("A failed analysis is shown with 500 and a fixed message only")
  void showsFailedAnalysisWith500() throws Exception {
    given(analysisService.load(ID))
        .willReturn(
            Optional.of(
                new ProjectResult(ID, saved(AnalysisStatus.FAILED, List.of()), null, false)));

    mockMvc
        .perform(get("/projects/" + ID))
        .andExpect(status().isInternalServerError())
        .andExpect(content().string(containsString("demo/pom.xml")))
        .andExpect(content().string(containsString("解析に失敗しました")))
        .andExpect(content().string(not(containsString("/apis"))));
  }

  @Test
  @DisplayName("IR of an older schema version asks the user to upload again")
  void asksToReuploadOutdatedIr() throws Exception {
    given(analysisService.load(ID))
        .willReturn(
            Optional.of(
                new ProjectResult(ID, saved(AnalysisStatus.SUCCEEDED, List.of()), null, true)));

    mockMvc
        .perform(get("/projects/" + ID))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("解析結果の版が古いため再アップロードしてください")))
        .andExpect(content().string(not(containsString("/apis"))));
  }

  @Test
  @DisplayName("An unknown project id shows a 404 page that links to the upload screen")
  void unknownProjectIs404() throws Exception {
    given(analysisService.load(ID)).willReturn(Optional.empty());

    mockMvc
        .perform(get("/projects/" + ID))
        .andExpect(status().isNotFound())
        .andExpect(view().name("project/not-found"))
        .andExpect(content().string(containsString("href=\"/\"")));
  }

  @Test
  @DisplayName("Text from the uploaded project is escaped, not rendered as HTML")
  void escapesUploadedText() throws Exception {
    given(analysisService.load(ID))
        .willReturn(
            Optional.of(
                new ProjectResult(
                    ID,
                    saved(
                        AnalysisStatus.UNSUPPORTED,
                        List.of(InspectionIssue.error("<script>alert(1)</script>"))),
                    null,
                    false)));

    mockMvc
        .perform(get("/projects/" + ID))
        .andExpect(content().string(not(containsString("<script>alert(1)</script>"))))
        .andExpect(content().string(containsString("&lt;script&gt;")));
  }
}
