package com.example.javaanalyzer.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.javaanalyzer.analysis.ProjectAnalyzer;
import com.example.javaanalyzer.analysis.plugin.mybatis.MyBatisPlugin;
import com.example.javaanalyzer.analysis.plugin.springboot.SpringBootPlugin;
import com.example.javaanalyzer.analysis.plugin.view.ViewTemplatePlugin;
import com.example.javaanalyzer.ir.ProjectModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** SCR-03 to SCR-10, rendered from the IR of the sample projects. */
@WebMvcTest(ResultPagesController.class)
class ResultPagesControllerTest {

  private static final String ID = "3f2b8c1e-7a4d-4f60-9b21-0c5de8a1f7aa";
  private static final String BASE = "/projects/" + ID;

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ProjectAnalysisService analysisService;

  private static ProjectModel analyze(String sample) {
    return new ProjectAnalyzer(
            List.of(new SpringBootPlugin(), new MyBatisPlugin(), new ViewTemplatePlugin()))
        .analyze(Path.of("src/test/resources/sample-projects", sample));
  }

  private void loadSample(String sample) {
    ProjectModel model = analyze(sample);
    given(analysisService.load(ID))
        .willReturn(
            Optional.of(
                new ProjectResult(
                    ID,
                    new SavedProjectResult(
                        "pom.xml", "3.3.4", "UTF-8", List.of(), AnalysisStatus.SUCCEEDED),
                    model,
                    false)));
  }

  @Test
  @DisplayName("SCR-03 shows the package diagram by default and the unresolved dependencies")
  void architecturePackages() throws Exception {
    loadSample("lombok-jpa-demo");

    mockMvc
        .perform(get(BASE + "/architecture"))
        .andExpect(status().isOk())
        .andExpect(view().name("project/architecture"))
        .andExpect(content().string(containsString("class=\"mermaid\"")))
        .andExpect(content().string(containsString("data-diagram-id=\"packages\"")))
        .andExpect(content().string(containsString("解決できなかった依存")))
        .andExpect(content().string(containsString("JavaMailSender")))
        .andExpect(content().string(containsString("外部ライブラリ")));
  }

  @Test
  @DisplayName("SCR-03 component tab can be filtered by package; the layer tab shows roles")
  void architectureComponentsAndLayers() throws Exception {
    loadSample("circular-dependency-demo");

    mockMvc
        .perform(
            get(BASE + "/architecture")
                .param("tab", "components")
                .param("package", "com.example.billing.web"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-diagram-id=\"components\"")))
        .andExpect(content().string(containsString("InvoiceController")))
        .andExpect(content().string(not(containsString("InvoiceRepository&quot;"))));
    mockMvc
        .perform(get(BASE + "/architecture").param("tab", "layers"))
        .andExpect(content().string(containsString("data-diagram-id=\"layers\"")));
  }

  @Test
  @DisplayName("SCR-04 lists the six API items and links the controller to its class page")
  void apis() throws Exception {
    loadSample("lombok-jpa-demo");

    mockMvc
        .perform(get(BASE + "/apis"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("API一覧（2件）")))
        .andExpect(content().string(containsString("<th>Response DTO</th>")))
        .andExpect(content().string(containsString("/api/orders/{id}")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "href=\"" + BASE + "/classes/com.example.shop.web.OrderController\"")));
  }

  @Test
  @DisplayName("SCR-05 shows the ER diagram and one section per entity with 未指定 names")
  void er() throws Exception {
    loadSample("lombok-jpa-demo");

    mockMvc
        .perform(get(BASE + "/er"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-diagram-id=\"er\"")))
        .andExpect(content().string(containsString("Product（エンティティ：テーブル 未指定）")))
        .andExpect(content().string(containsString("BaseEntity（MappedSuperclass）")));
  }

  @Test
  @DisplayName("SCR-06 lists classes with filters, and the detail page uses the Service template")
  void classes() throws Exception {
    loadSample("lombok-jpa-demo");

    mockMvc
        .perform(get(BASE + "/classes").param("role", "Service"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("OrderService")))
        .andExpect(content().string(not(containsString(">OrderController<"))));
    mockMvc
        .perform(get(BASE + "/classes/com.example.shop.service.OrderService"))
        .andExpect(status().isOk())
        .andExpect(view().name("project/class-detail"))
        .andExpect(content().string(containsString("OrderService（Service）")))
        .andExpect(content().string(containsString("Lombok（@RequiredArgsConstructor）")))
        .andExpect(content().string(containsString("呼び出し関係（簡易）")))
        .andExpect(content().string(containsString("PurchaseOrderRepository.findById")));
  }

  @Test
  @DisplayName("SCR-06 detail of a class not in the IR is 404")
  void unknownClassIs404() throws Exception {
    loadSample("lombok-jpa-demo");

    mockMvc.perform(get(BASE + "/classes/com.example.Missing")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("SCR-07 lists issues with Japanese names and can filter by kind")
  void issues() throws Exception {
    loadSample("lombok-jpa-demo");

    mockMvc
        .perform(get(BASE + "/issues").param("kind", "SYNTAX_ERROR"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("構文エラー")))
        .andExpect(content().string(containsString("BrokenUtil.java")))
        .andExpect(content().string(containsString("コンパイル失敗を完全には検出しない")));
    mockMvc.perform(get(BASE + "/issues").param("kind", "NOT_A_KIND")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("SCR-08 shows mappers and SQL exactly as written, escaped as text")
  void mybatis() throws Exception {
    loadSample("mybatis-demo");

    mockMvc
        .perform(get(BASE + "/mybatis"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("BookMapper")))
        .andExpect(content().string(containsString("&lt;include refid=&quot;columns&quot;/&gt;")))
        .andExpect(content().string(containsString("基底インタフェースの自動生成メソッドは表示しない")));
  }

  @Test
  @DisplayName("SCR-09 shows @Bean definitions and @ConfigurationProperties")
  void configuration() throws Exception {
    loadSample("spring-config-demo");

    mockMvc
        .perform(get(BASE + "/configuration"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("@Bean 定義")))
        .andExpect(content().string(containsString("MailConfig")))
        .andExpect(content().string(containsString("MailProperties")));
  }

  @Test
  @DisplayName("SCR-10 shows screens, the transition diagram and transitions")
  void views() throws Exception {
    loadSample("thymeleaf-demo");

    mockMvc
        .perform(get(BASE + "/views"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-diagram-id=\"screens\"")))
        .andExpect(content().string(containsString("PetController.list")))
        .andExpect(content().string(containsString("画面ごとの遷移先")));
  }

  @Test
  @DisplayName("SCR-10 names the template directory once and lists templates relative to it")
  void viewsRelativePaths() throws Exception {
    loadSample("thymeleaf-demo");

    mockMvc
        .perform(get(BASE + "/views"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("テンプレートの場所：src/main/resources/templates/")))
        .andExpect(content().string(containsString("<div class=\"cell\">pets/petList.html</div>")))
        .andExpect(content().string(not(containsString(">src/main/resources/templates/"))));
  }

  @Test
  @DisplayName("Empty results say 検出されませんでした instead of hiding the screen")
  void emptyScreens() throws Exception {
    loadSample("lombok-jpa-demo");

    mockMvc
        .perform(get(BASE + "/mybatis"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("検出されませんでした")));
    mockMvc.perform(get(BASE + "/views")).andExpect(content().string(containsString("検出されませんでした")));
  }

  @Test
  @DisplayName("A project that was not analyzed is redirected to SCR-02; an unknown one is 404")
  void notAnalyzedAndUnknown() throws Exception {
    given(analysisService.load(ID))
        .willReturn(
            Optional.of(
                new ProjectResult(
                    ID,
                    new SavedProjectResult(null, null, "UTF-8", List.of(), AnalysisStatus.FAILED),
                    null,
                    false)));
    mockMvc
        .perform(get(BASE + "/apis"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(BASE));

    given(analysisService.load(ID)).willReturn(Optional.empty());
    mockMvc.perform(get(BASE + "/er")).andExpect(status().isNotFound());
  }
}
