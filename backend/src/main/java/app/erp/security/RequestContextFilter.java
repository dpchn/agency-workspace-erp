package app.erp.security;

import app.erp.common.CurrentUser;
import app.erp.common.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds tenant + principal from the verified JWT into {@link TenantContext}.
 * Tenant is NEVER taken from headers, query params or bodies.
 */
public class RequestContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String cid = req.getHeader("X-Correlation-Id");
        if (cid == null || cid.isBlank() || cid.length() > 64) cid = UUID.randomUUID().toString();
        MDC.put("correlationId", cid);
        res.setHeader("X-Correlation-Id", cid);
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwt) {
                var t = jwt.getToken();
                String role = t.getClaimAsString("role");
                TenantContext.set(new CurrentUser(
                        UUID.fromString(t.getSubject()),
                        uuid(t.getClaimAsString("tid")),
                        uuid(t.getClaimAsString("mid")),
                        role == null ? null : Role.valueOf(role),
                        uuids(t.getClaimAsStringList("br")),
                        uuids(t.getClaimAsStringList("st")),
                        uuid(t.getClaimAsString("cid")),
                        uuid(t.getClaimAsString("eid")),
                        Boolean.TRUE.equals(t.getClaimAsBoolean("pa")),
                        t.getClaimAsString("name")));
                MDC.put("tenantId", String.valueOf(t.getClaimAsString("tid")));
            }
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
            MDC.clear();
        }
    }

    private static UUID uuid(String s) { return s == null ? null : UUID.fromString(s); }

    private static List<UUID> uuids(List<String> l) { return l == null ? null : l.stream().map(UUID::fromString).toList(); }
}
