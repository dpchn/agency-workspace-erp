package app.erp.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Append-only audit trail. Stores hashes of before/after state (tamper evidence without
 * duplicating PII) plus a small details map.
 */
@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void record(String action, String entityType, UUID entityId, Object before, Object after, Map<String, ?> details) {
        UUID tenant = TenantContext.tenantId();
        if (tenant == null) return;
        CurrentUser u = TenantContext.user();
        jdbc.update("""
                insert into audit_events(id, tenant_id, actor_user_id, action, entity_type, entity_id,
                    before_hash, after_hash, details, correlation_id)
                values (?,?,?,?,?,?,?,?,?::jsonb,?)""",
                UUID.randomUUID(), tenant, u == null ? null : u.userId(), action, entityType, entityId,
                hash(before), hash(after), json(details), MDC.get("correlationId"));
    }

    public void record(String action, String entityType, UUID entityId, Map<String, ?> details) {
        record(action, entityType, entityId, null, null, details);
    }

    private String json(Object o) {
        try { return o == null ? null : mapper.writeValueAsString(o); } catch (Exception e) { return null; }
    }

    private String hash(Object o) {
        if (o == null) return null;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(json(o).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            return null;
        }
    }
}
