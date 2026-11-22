package app.erp.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Transactional outbox: events are written in the same DB transaction as the state change. */
@Service
public class OutboxService {
    public static final String ATTENDANCE_APPROVED = "AttendanceApproved";
    public static final String PAYROLL_APPROVED = "PayrollApproved";
    public static final String INVOICE_ISSUED = "InvoiceIssued";
    public static final String PAYMENT_RECORDED = "PaymentRecorded";
    public static final String INCIDENT_RAISED = "IncidentRaised";
    public static final String INCIDENT_ESCALATED = "IncidentEscalated";
    public static final String SHIFT_UNCOVERED = "ShiftUncovered";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OutboxService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void publish(String type, UUID aggregateId, Map<String, ?> payload) {
        try {
            jdbc.update("insert into outbox_events(id, tenant_id, event_type, aggregate_id, payload) values (?,?,?,?,?::jsonb)",
                    UUID.randomUUID(), TenantContext.requireTenant(), type, aggregateId, mapper.writeValueAsString(payload));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
