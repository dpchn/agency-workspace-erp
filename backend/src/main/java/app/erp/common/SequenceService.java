package app.erp.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Gap-free per-tenant document numbering (row lock on the sequence row). */
@Service
public class SequenceService {
    private final JdbcTemplate jdbc;

    public SequenceService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long next(String key) {
        var tenant = TenantContext.requireTenant();
        return jdbc.queryForObject("""
                insert into number_sequences(tenant_id, seq_key, next_value) values (?, ?, 2)
                on conflict (tenant_id, seq_key) do update set next_value = number_sequences.next_value + 1
                returning next_value - 1""", Long.class, tenant, key);
    }
}
