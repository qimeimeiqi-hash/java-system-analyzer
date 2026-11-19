package com.example.javaanalyzer.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HomeController.class)
class HomeControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("GET / renders the index view with the application title")
  void rootPathRendersIndexViewWithTitle() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(view().name("index"))
        .andExpect(content().string(containsString("<title>Javaシステム解析支援ツール</title>")));
  }

  @Test
  @DisplayName("GET / shows a multipart upload form posting a zip file to /upload")
  void rootPathShowsUploadForm() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("action=\"/upload\"")))
        .andExpect(content().string(containsString("enctype=\"multipart/form-data\"")))
        .andExpect(content().string(containsString("name=\"file\"")));
  }

  @Test
  @DisplayName("GET on an unmapped path returns 404")
  void unmappedPathReturnsNotFound() throws Exception {
    mockMvc.perform(get("/no-such-page")).andExpect(status().isNotFound());
  }
}
