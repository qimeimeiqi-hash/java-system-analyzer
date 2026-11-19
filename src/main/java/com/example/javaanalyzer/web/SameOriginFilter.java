package com.example.javaanalyzer.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Defense in depth for a tool that runs on the user's machine (No.16 F-05).
 *
 * <ul>
 *   <li>Refuses a state-changing request (upload, export) that a browser sends from another site's
 *       page: its {@code Origin}, or else its {@code Referer}, must be this server. A request
 *       carrying neither (curl, scripts) is allowed, since only browsers forge requests for a
 *       visitor.
 *   <li>Sends a Content-Security-Policy: scripts, styles, images and connections come from this
 *       site only (images also from {@code data:}/{@code blob:} for diagram PNGs), so text from an
 *       uploaded project cannot load anything from elsewhere even if it reached the page.
 * </ul>
 */
@Component
public class SameOriginFilter extends OncePerRequestFilter {

  static final String CONTENT_SECURITY_POLICY =
      "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
          + "img-src 'self' data: blob:; object-src 'none'; base-uri 'self'; "
          + "form-action 'self'; frame-ancestors 'none'";

  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
    if (!SAFE_METHODS.contains(request.getMethod()) && !fromThisSite(request)) {
      response.sendError(HttpStatus.FORBIDDEN.value());
      return;
    }
    chain.doFilter(request, response);
  }

  private static boolean fromThisSite(HttpServletRequest request) {
    String origin = request.getHeader("Origin");
    if (origin == null) {
      String referer = request.getHeader("Referer");
      if (referer == null) {
        return true;
      }
      origin = originOf(referer);
    }
    return ownOrigin(request).equalsIgnoreCase(origin);
  }

  /** The scheme, host and port of a URL, as an Origin header writes them; "" when malformed. */
  private static String originOf(String url) {
    try {
      URI uri = URI.create(url);
      if (uri.getScheme() == null || uri.getHost() == null) {
        return "";
      }
      return uri.getScheme()
          + "://"
          + uri.getHost()
          + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    } catch (IllegalArgumentException e) {
      return "";
    }
  }

  private static String ownOrigin(HttpServletRequest request) {
    String scheme = request.getScheme();
    int port = request.getServerPort();
    boolean defaultPort =
        ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
    return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
  }
}
