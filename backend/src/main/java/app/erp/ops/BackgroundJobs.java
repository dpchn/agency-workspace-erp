package app.erp.ops;

import app.erp.attendance.AttendanceService;
import app.erp.billing.BillingService;
import app.erp.common.AppProperties;
import app.erp.common.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Per-tenant scheduled work. Each tenant runs in its own transaction with RLS bound to that tenant,
 * so a failure in one agency never blocks another. Swap for a durable queue when scale requires.
 */
@Slf4j
@Component
public class BackgroundJobs {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AttendanceService attendance;
    private final BillingService billing;
    private final AppProperties props;

    public BackgroundJobs(JdbcTemplate jdbc, TransactionTemplate tx, AttendanceService attendance, BillingService billing, AppProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.attendance = attendance;
        this.billing = billing;
        this.props = props;
    }

    private List<UUID> activeTenants() {
        return jdbc.queryForList("select id from tenants where status in ('TRIAL','ACTIVE')", UUID.class);
    }

    private void forEachTenant(String job, java.util.function.Consumer<UUID> work) {
        if (!props.jobs().enabled()) return;
        for (UUID t : activeTenants()) {
            try {
                TenantContext.runAs(t, () -> tx.execute(s -> { work.accept(t); return null; }));
            } catch (Exception e) {
                log.warn("Job {} failed for tenant {}: {}", job, t, e.getMessage());
            }
        }
    }

    /** Close out duties that ended 2h+ ago: no-shows become ABSENT, missing check-outs get flagged. */
    @Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT2M")
    public void finalizeAttendance() {
        forEachTenant("finalizeAttendance", t -> attendance.finalizeEnded(Instant.now().minusSeconds(7200)));
    }

    /** Escalate unacknowledged incidents past their response deadline (repeats every SLA window). */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void escalateIncidents() {
        forEachTenant("escalateIncidents", t -> {
            var due = jdbc.queryForList("""
                    update incidents set escalation_level = escalation_level + 1,
                      response_due_at = now() + (response_due_at - created_at), updated_at = now()
                    where status = 'OPEN' and response_due_at < now() returning id, severity, escalation_level""");
            for (var row : due) {
                jdbc.update("insert into outbox_events(id, tenant_id, event_type, aggregate_id, payload) values (?,?,?,?,?::jsonb)",
                        UUID.randomUUID(), t, "IncidentEscalated", row.get("id"),
                        "{\"severity\":\"" + row.get("severity") + "\",\"level\":" + row.get("escalation_level") + "}");
            }
        });
    }

    @Scheduled(cron = "0 15 1 * * *")
    public void overdueInvoices() {
        forEachTenant("overdueInvoices", t -> billing.markOverdue());
    }

    /**
     * Outbox relay. Turns domain events into notification records (push/SMS/e-mail providers plug in
     * here). At-least-once: events are marked published only after handling.
     */
    @Scheduled(fixedDelayString = "PT15S", initialDelayString = "PT30S")
    public void relayOutbox() {
        if (!props.jobs().enabled()) return;
        tx.execute(s -> {
            var rows = jdbc.queryForList("""
                    select id, tenant_id, event_type, payload::text as payload from outbox_events
                    where published_at is null and attempts < 10 order by created_at limit 100 for update skip locked""");
            for (var r : rows) {
                try {
                    String channel = switch ((String) r.get("event_type")) {
                        case "IncidentRaised", "IncidentEscalated", "ShiftUncovered" -> "PUSH";
                        case "InvoiceIssued", "PaymentRecorded", "AttendanceDisputed" -> "EMAIL";
                        default -> null;
                    };
                    if (channel != null) {
                        jdbc.update("insert into notifications(id, tenant_id, channel, template, payload) values (?,?,?,?,?::jsonb)",
                                UUID.randomUUID(), r.get("tenant_id"), channel, r.get("event_type"), r.get("payload"));
                    }
                    jdbc.update("update outbox_events set published_at = now(), attempts = attempts + 1 where id = ?", r.get("id"));
                } catch (Exception e) {
                    jdbc.update("update outbox_events set attempts = attempts + 1, last_error = ? where id = ?", e.getMessage(), r.get("id"));
                }
            }
            return null;
        });
    }
}
