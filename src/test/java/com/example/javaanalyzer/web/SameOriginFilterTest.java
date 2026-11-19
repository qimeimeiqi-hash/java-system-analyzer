package com.example.javaanalyzer.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SameOriginFilterTest {

  private final SameOriginFilter filter = new SameOriginFilter();

  /** A request to http://localhost:8080 as a browser sends it. */
  private static MockHttpServletRequest request(String method) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, "/upload");
    request.setScheme("http");
    request.setServerName("localhost");
    request.setServerPort(8080);
    return request;
  }

  /** Runs the filter; the status stays 200 only when the request was passed on. */
  private MockHttpServletResponse run(MockHttpServletRequest request)
      throws ServletException, IOException {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertThat(chain.getRequest() != null)
        .as("passed on to the application")
        .isEqualTo(response.getStatus() == 200);
    return response;
  }

  @Test
  @DisplayName("A POST from another site's page is refused with 403")
  void crossOriginPostIsRefused() throws Exception {
    MockHttpServletRequest request = request("POST");
    request.addHeader("Origin", "http://evil.example");

    assertThat(run(request).getStatus()).isEqualTo(403);
  }

  @Test
  @DisplayName("A POST whose only clue is a Referer from another site is refused")
  void crossOriginRefererIsRefused() throws Exception {
    MockHttpServletRequest request = request("POST");
    request.addHeader("Referer", "http://evil.example/page");

    assertThat(run(request).getStatus()).isEqualTo(403);
  }

  @Test
  @DisplayName("A POST from this tool's own pages is allowed")
  void sameOriginPostIsAllowed() throws Exception {
    MockHttpServletRequest withOrigin = request("POST");
    withOrigin.addHeader("Origin", "http://localhost:8080");
    MockHttpServletRequest withReferer = request("POST");
    withReferer.addHeader("Referer", "http://localhost:8080/projects/x/export");

    assertThat(run(withOrigin).getStatus()).isEqualTo(200);
    assertThat(run(withReferer).getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("A POST without Origin or Referer (curl, scripts) is allowed")
  void postWithoutBrowserHeadersIsAllowed() throws Exception {
    assertThat(run(request("POST")).getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("A GET is never refused, even from another site")
  void getIsAllowed() throws Exception {
    MockHttpServletRequest request = request("GET");
    request.addHeader("Origin", "http://evil.example");

    assertThat(run(request).getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("Every response restricts scripts, images and styles to this site")
  void contentSecurityPolicyIsSet() throws Exception {
    String policy = run(request("GET")).getHeader("Content-Security-Policy");

    assertThat(policy)
        .contains("default-src 'self'")
        .contains("script-src 'self'")
        .contains("img-src 'self' data: blob:")
        .contains("frame-ancestors 'none'")
        .doesNotContain("unsafe-eval");
  }
}
