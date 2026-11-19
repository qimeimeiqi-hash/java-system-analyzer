package com.example.javaanalyzer.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(IrFileController.class)
class IrFileControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ProjectAnalysisService analysisService;

  @TempDir Path tempDir;

  @Test
  @DisplayName("GET project.json returns the saved file as UTF-8 JSON")
  void returnsIndexFile() throws Exception {
    Path file = Files.writeString(tempDir.resolve("project.json"), "{\"rootPackage\":\"注文\"}");
    given(analysisService.irFile("p1", "project.json")).willReturn(Optional.of(file));

    mockMvc
        .perform(get("/projects/p1/ir/project.json"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().bytes("{\"rootPackage\":\"注文\"}".getBytes("UTF-8")));
  }

  @Test
  @DisplayName("GET a package file looks it up under packages/ and keeps dots in the name")
  void returnsPackageFile() throws Exception {
    Path file = Files.writeString(tempDir.resolve("pkg.json"), "{\"packageName\":\"com.example\"}");
    given(analysisService.irFile("p1", "packages/com.example.json")).willReturn(Optional.of(file));

    mockMvc
        .perform(get("/projects/p1/ir/packages/com.example.json"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("com.example")));
  }

  @Test
  @DisplayName("Returns 404 when the service does not find the file")
  void returnsNotFound() throws Exception {
    given(analysisService.irFile("missing", "project.json")).willReturn(Optional.empty());

    mockMvc.perform(get("/projects/missing/ir/project.json")).andExpect(status().isNotFound());
  }
}
