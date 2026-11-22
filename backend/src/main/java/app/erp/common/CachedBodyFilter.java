package app.erp.common;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** Buffers request bodies of requests carrying Idempotency-Key so they can be hashed and replayed. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CachedBodyFilter extends OncePerRequestFilter {
    public static final String BODY_ATTR = "cached.body";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        if (req.getHeader("Idempotency-Key") == null) {
            chain.doFilter(req, res);
            return;
        }
        byte[] body = req.getInputStream().readAllBytes();
        req.setAttribute(BODY_ATTR, new String(body, StandardCharsets.UTF_8));
        HttpServletRequest wrapped = new HttpServletRequestWrapper(req) {
            @Override
            public ServletInputStream getInputStream() {
                ByteArrayInputStream in = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    public boolean isFinished() { return in.available() == 0; }
                    public boolean isReady() { return true; }
                    public void setReadListener(ReadListener l) { }
                    public int read() { return in.read(); }
                };
            }

            @Override
            public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
        };
        ContentCachingResponseWrapper resWrapper = new ContentCachingResponseWrapper(res);
        try {
            chain.doFilter(wrapped, resWrapper);
        } finally {
            resWrapper.copyBodyToResponse();
        }
    }
}
