package com.example.javaanalyzer.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.javaanalyzer.upload.InspectionIssue;
import com.example.javaanalyzer.upload.InspectionResult;
import com.example.javaanalyzer.upload.ProjectUploadService;
import com.example.javaanalyzer.upload.UploadException;
import com.example.javaanalyzer.upload.UploadResult;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@WebMvcTest(UploadController.class)
class UploadControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ProjectUploadService uploadService;

  @MockitoBean private ProjectAnalysisService analysisService;

  private static final MockMultipartFile ZIP =
      new MockMultipartFile("file", "demo.zip", "application/zip", new byte[] {1, 2, 3});

  private static UploadResult supportedResult(String projectId) {
    return new UploadResult(
        projectId,
        new InspectionResult(
            Path.of("workspace/p1/source/demo"), "demo/pom.xml", "3.2.5", List.of()));
  }

  @Test
  @DisplayName("A supported project is analyzed and saved, then redirected to its result page")
  void analyzesSupportedProjectAndRedirects() throws Exception {
    UploadResult result = supportedResult("p1");
    given(uploadService.upload(any())).willReturn(result);
    given(analysisService.analyzeAndSave(result)).willReturn(AnalysisStatus.SUCCEEDED);

    mockMvc
        .perform(multipart("/upload").file(ZIP))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/projects/p1"));

    verify(analysisService).analyzeAndSave(result);
  }

  @Test
  @DisplayName("An unsupported or failed project is also redirected; the result page shows why")
  void redirectsUnsupportedAndFailedProjects() throws Exception {
    InspectionResult inspection =
        new InspectionResult(null, null, null, List.of(InspectionIssue.error("pom.xml が見つかりません。")));
    UploadResult unsupported = new UploadResult("p2", inspection);
    given(uploadService.upload(any())).willReturn(unsupported);
    given(analysisService.analyzeAndSave(unsupported)).willReturn(AnalysisStatus.UNSUPPORTED);

    mockMvc
        .perform(multipart("/upload").file(ZIP))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/projects/p2"));
  }

  @Test
  @DisplayName(
      "POST /upload returns to the index page with the message when the service rejects the file")
  void showsErrorMessageOnUploadException() throws Exception {
    given(uploadService.upload(any())).willThrow(new UploadException("zip形式のファイルを指定してください。"));

    mockMvc
        .perform(multipart("/upload").file(ZIP))
        .andExpect(status().isBadRequest())
        .andExpect(view().name("index"))
        .andExpect(model().attribute("errorMessage", "zip形式のファイルを指定してください。"))
        .andExpect(content().string(containsString("zip形式のファイルを指定してください。")));
  }

  @Test
  @DisplayName("POST /upload without a file part is handled by the service instead of a raw 400")
  void missingFilePartIsDelegatedToService() throws Exception {
    given(uploadService.upload(any())).willThrow(new UploadException("ファイルが選択されていないか、空です。"));

    mockMvc
        .perform(multipart("/upload"))
        .andExpect(status().isBadRequest())
        .andExpect(view().name("index"))
        .andExpect(content().string(containsString("ファイルが選択されていないか、空です。")));
  }

  @Test
  @DisplayName("Upload size limit exceeded is shown as a message with 413")
  void showsMessageWhenUploadSizeExceeded() throws Exception {
    given(uploadService.upload(any())).willThrow(new MaxUploadSizeExceededException(50));

    mockMvc
        .perform(multipart("/upload").file(ZIP))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(view().name("index"))
        .andExpect(content().string(containsString("上限")));
  }
}
