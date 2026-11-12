package dev.queuebridge;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bound JSON before Jackson allocates domain objects, including chunked requests. */
@Component
class RequestLimits extends OncePerRequestFilter {
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!request.getRequestURI().equals("/api/orders") || !request.getMethod().equals("POST")) {
      chain.doFilter(request, response);
      return;
    }
    byte[] body = request.getInputStream().readNBytes(16385);
    if (body.length > 16384) {
      response.sendError(413, "Request exceeds 16 KiB");
      return;
    }
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          @Override
          public ServletInputStream getInputStream() {
            var bytes = new ByteArrayInputStream(body);
            return new ServletInputStream() {
              public int read() {
                return bytes.read();
              }

              public boolean isFinished() {
                return bytes.available() == 0;
              }

              public boolean isReady() {
                return true;
              }

              public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Synchronous endpoint");
              }
            };
          }
        },
        response);
  }
}
