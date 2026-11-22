package app.erp.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Idempotency-Key handling for @Idempotent endpoints (check-ins, approvals, invoice
 * creation, payments, async jobs). First request reserves the key; replays with the same
 * key get the stored response; a different request body with the same key gets 422.
 */
@Configuration
public class IdempotencyInterceptor implements HandlerInterceptor, WebMvcConfigurer {
    private static final String ATTR = "idem.key";
    private final JdbcTemplate jdbc;

    public IdempotencyInterceptor(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod hm) || !hm.hasMethodAnnotation(Idempotent.class)) return true;
        String key = req.getHeader("Idempotency-Key");
        if (key == null || key.isBlank() || key.length() > 100)
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header is required");
        UUID tenant = TenantContext.requireTenant();
        String scopedKey = req.getMethod() + " " + req.getRequestURI() + " " + key;
        if (scopedKey.length() > 100) scopedKey = sha(scopedKey);
        String reqHash = sha(req.getMethod() + req.getRequestURI() + bodyOf(req));
        try {
            jdbc.update("insert into idempotency_keys(tenant_id, idem_key, request_hash) values (?,?,?)", tenant, scopedKey, reqHash);
            req.setAttribute(ATTR, scopedKey);
            return true;
        } catch (DuplicateKeyException dup) {
            var row = jdbc.queryForMap("select request_hash, status_code, response_body from idempotency_keys where tenant_id=? and idem_key=?",
                    tenant, scopedKey);
            if (!reqHash.equals(row.get("request_hash")))
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key reused with a different request");
            if (row.get("status_code") == null)
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENT_REQUEST_IN_PROGRESS", "Request with this key is still processing");
            res.setStatus((Integer) row.get("status_code"));
            res.setContentType("application/json");
            res.setHeader("Idempotent-Replay", "true");
            String body = (String) row.get("response_body");
            if (body != null) res.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        String key = (String) req.getAttribute(ATTR);
        if (key == null) return;
        UUID tenant = TenantContext.tenantId();
        if (tenant == null) return;
        if (res.getStatus() >= 500 || ex != null) {
            jdbc.update("delete from idempotency_keys where tenant_id=? and idem_key=?", tenant, key); // allow retry
            return;
        }
        String body = null;
        ContentCachingResponseWrapper w = org.springframework.web.util.WebUtils.getNativeResponse(res, ContentCachingResponseWrapper.class);
        if (w != null) body = new String(w.getContentAsByteArray(), StandardCharsets.UTF_8);
        jdbc.update("update idempotency_keys set status_code=?, response_body=? where tenant_id=? and idem_key=?",
                res.getStatus(), body, tenant, key);
    }

    private static String bodyOf(HttpServletRequest req) {
        Object cached = req.getAttribute(CachedBodyFilter.BODY_ATTR);
        return cached == null ? "" : (String) cached;
    }

    static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
