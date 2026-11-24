package app.erp.attendance;

import app.erp.common.ApiException;
import app.erp.common.AuditService;
import app.erp.common.OutboxService;
import app.erp.security.Authz;
import app.erp.security.Perm;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Client confirmation of attendance. The client sees, per site and day, how many people were required
 * and how many actually reported, then confirms the month or disputes specific days. A dispute must be
 * resolved by the agency before that month's invoice can be issued (setting blockInvoiceOnDispute).
 */
@RestController
@RequestMapping("/v1")
public class SignoffController {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final OutboxService outbox;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired private app.erp.platform.TenantService tenantService;

    private boolean enabled() { return tenantService.settings().bool("clientSignoffEnabled"); }

    public SignoffController(JdbcTemplate jdbc, AuditService audit, OutboxService outbox, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    /** Client portal: month sheet for one of the client's sites. Guard names and daily status only, no pay data. */
    @GetMapping("/portal/attendance")
    @Transactional(readOnly = true)
    public Map<String, Object> clientSheet(@RequestParam String month, @RequestParam(required = false) UUID siteId) {
        var u = Authz.require(Perm.CLIENT_PORTAL);
        YearMonth ym = YearMonth.parse(month);
        List<Map<String, Object>> sites = jdbc.queryForList("select id, name from sites where client_id = ? order by name", u.clientId());
        if (sites.isEmpty()) return Map.of("month", month, "sites", sites);
        UUID site = siteId != null ? siteId : (UUID) sites.get(0).get("id");
        if (sites.stream().noneMatch(s -> site.equals(s.get("id")))) throw ApiException.notFound("Site");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month);
        out.put("daysInMonth", ym.lengthOfMonth());
        out.put("sites", sites);
        out.put("siteId", site);
        out.putAll(sheet(site, ym));
        out.put("signoff", signoff(site, ym.atDay(1)));
        out.put("signoffEnabled", enabled());
        return out;
    }

    private Map<String, Object> sheet(UUID site, YearMonth ym) {
        LocalDate from = ym.atDay(1), to = ym.atEndOfMonth();
        // required vs reported per day (client-requested extras count; agency's own extra cover does not)
        Map<Integer, Map<String, Object>> days = new TreeMap<>();
        jdbc.query("""
                select extract(day from s.duty_date)::int as d, sum(s.required_headcount) as required
                from shifts s where s.site_id = ? and s.duty_date between ? and ? and s.status <> 'CANCELLED'
                  and (s.kind = 'REGULAR' or s.client_requested) group by 1""", rs -> {
            days.computeIfAbsent(rs.getInt(1), k -> new LinkedHashMap<>(Map.of("day", k, "required", 0, "reported", BigDecimal.ZERO)))
                    .put("required", rs.getInt(2));
        }, site, from, to);
        jdbc.query("""
                select extract(day from r.duty_date)::int as d,
                       coalesce(sum(case when r.status = 'PENDING' and r.check_in_at is not null then 1 else r.bill_units end), 0)
                from attendance_records r where r.site_id = ? and r.duty_date between ? and ? and r.billable
                  and r.approval_status <> 'REJECTED' group by 1""", rs -> {
            days.computeIfAbsent(rs.getInt(1), k -> new LinkedHashMap<>(Map.of("day", k, "required", 0, "reported", BigDecimal.ZERO)))
                    .put("reported", rs.getBigDecimal(2));
        }, site, from, to);
        Map<UUID, Map<String, Object>> people = new LinkedHashMap<>();
        jdbc.query("""
                select r.employee_id, e.employee_code, e.first_name || coalesce(' ' || e.last_name, ''), st.name,
                       extract(day from r.duty_date)::int, r.status, r.bill_units
                from attendance_records r join employees e on e.id = r.employee_id join service_types st on st.id = r.service_type_id
                where r.site_id = ? and r.duty_date between ? and ? and r.billable
                order by e.employee_code, r.duty_date""", rs -> {
            var p = people.computeIfAbsent((UUID) rs.getObject(1), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                try {
                    m.put("code", rs.getString(2));
                    m.put("name", rs.getString(3));
                    m.put("role", rs.getString(4));
                } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
                m.put("days", new TreeMap<Integer, String>());
                m.put("duties", BigDecimal.ZERO);
                return m;
            });
            String code = switch (rs.getString(6)) {
                case "PRESENT" -> "P"; case "HALF_DAY" -> "H"; case "ABSENT" -> "A"; case "ON_LEAVE" -> "L"; default -> "-";
            };
            @SuppressWarnings("unchecked") var d = (TreeMap<Integer, String>) p.get("days");
            d.merge(rs.getInt(5), code, (a, b) -> a + "/" + b);
            p.put("duties", ((BigDecimal) p.get("duties")).add(rs.getBigDecimal(7)));
        }, site, from, to);
        BigDecimal reported = days.values().stream().map(x -> (BigDecimal) x.get("reported")).reduce(BigDecimal.ZERO, BigDecimal::add);
        int required = days.values().stream().mapToInt(x -> (Integer) x.get("required")).sum();
        return Map.of("days", days.values(), "people", people.values(), "totals", Map.of("required", required, "reported", reported));
    }

    private Map<String, Object> signoff(UUID site, LocalDate month) {
        var l = jdbc.queryForList("""
                select so.id, so.status, so.note, so.disputes, so.duties_at_signoff as "dutiesAtSignoff", so.signed_at as "signedAt",
                       u.full_name as "signedBy", so.resolved_at as "resolvedAt", so.resolution_note as "resolutionNote"
                from attendance_signoffs so left join users u on u.id = so.signed_by
                where so.site_id = ? and so.period_month = ?""", site, month);
        return l.isEmpty() ? null : l.get(0);
    }

    public record Dispute(LocalDate date, String note) {}

    public record SignoffRequest(UUID siteId, String month, String status, String note, List<Dispute> disputes) {}

    /** Client confirms the month for a site, or disputes it (with the days and what was wrong). */
    @PostMapping("/portal/attendance/signoff")
    @Transactional
    public Map<String, Object> signoff(@RequestBody SignoffRequest r) throws Exception {
        var u = Authz.require(Perm.CLIENT_SIGNOFF);
        if (!enabled()) throw ApiException.invalidState("Attendance sign-off by clients is not used by this agency");
        if (r.siteId() == null) throw ApiException.field("siteId", "Required");
        if (!Set.of("CONFIRMED", "DISPUTED").contains(String.valueOf(r.status()))) throw ApiException.field("status", "CONFIRMED or DISPUTED");
        YearMonth ym;
        try { ym = YearMonth.parse(String.valueOf(r.month())); } catch (Exception e) { throw ApiException.field("month", "YYYY-MM"); }
        if (ym.atDay(1).isAfter(LocalDate.now())) throw ApiException.field("month", "Month has not started");
        Long own = jdbc.queryForObject("select count(*) from sites where id = ? and client_id = ?", Long.class, r.siteId(), u.clientId());
        if (own == null || own == 0) throw ApiException.notFound("Site");
        List<Dispute> disputes = r.disputes() == null ? List.of() : r.disputes();
        if ("DISPUTED".equals(r.status()) && (r.note() == null || r.note().isBlank()) && disputes.isEmpty())
            throw ApiException.field("note", "Say what is wrong");
        for (Dispute d : disputes)
            if (d.date() == null || !YearMonth.from(d.date()).equals(ym)) throw ApiException.field("disputes", "Dates must be within " + ym);
        BigDecimal duties = jdbc.queryForObject("""
                select coalesce(sum(bill_units),0) from attendance_records
                where site_id = ? and duty_date between ? and ? and billable and approval_status <> 'REJECTED'""",
                BigDecimal.class, r.siteId(), ym.atDay(1), ym.atEndOfMonth());
        String json = mapper.writeValueAsString(disputes.stream().map(d -> Map.of("date", d.date().toString(), "note", String.valueOf(d.note()))).toList());
        UUID id = jdbc.query("""
                insert into attendance_signoffs (id, tenant_id, client_id, site_id, period_month, status, note, disputes, duties_at_signoff, signed_by)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                on conflict (tenant_id, site_id, period_month) do update set status = excluded.status, note = excluded.note,
                  disputes = excluded.disputes, duties_at_signoff = excluded.duties_at_signoff, signed_by = excluded.signed_by,
                  signed_at = now(), resolved_by = null, resolved_at = null, resolution_note = null, updated_at = now(),
                  version = attendance_signoffs.version + 1
                returning id""", rs -> rs.next() ? (UUID) rs.getObject(1) : null,
                u.tenantId(), u.clientId(), r.siteId(), ym.atDay(1), r.status(), r.note(), json, duties, u.userId());
        audit.record("ATTENDANCE_CLIENT_" + r.status(), "Site", r.siteId(), Map.of("month", ym.toString(), "duties", duties));
        if ("DISPUTED".equals(r.status()))
            outbox.publish(OutboxService.ATTENDANCE_DISPUTED, id, Map.of("siteId", r.siteId().toString(), "month", ym.toString()));
        return Map.of("id", id, "status", r.status(), "dutiesAtSignoff", duties);
    }

    /** Agency view: sign-off status of every site that had duties in the month. */
    @GetMapping("/attendance/signoffs")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(@RequestParam String month) {
        Authz.requireAny(Perm.ATTENDANCE_READ, Perm.BILLING_READ);
        var u = Authz.user();
        YearMonth ym = YearMonth.parse(month);
        StringBuilder sql = new StringBuilder("""
                select si.id as "siteId", si.name as "siteName", c.id as "clientId", coalesce(c.display_name, c.legal_name) as "clientName",
                       (select coalesce(sum(r.bill_units),0) from attendance_records r where r.site_id = si.id and r.duty_date between ? and ?
                          and r.billable and r.approval_status <> 'REJECTED') as duties,
                       so.id, coalesce(so.status, 'NOT_SUBMITTED') as status, so.note, so.disputes, so.duties_at_signoff as "dutiesAtSignoff",
                       so.signed_at as "signedAt", su.full_name as "signedBy", so.resolved_at as "resolvedAt",
                       so.resolution_note as "resolutionNote", ru.full_name as "resolvedBy"
                from sites si join clients c on c.id = si.client_id
                left join attendance_signoffs so on so.site_id = si.id and so.period_month = ?
                left join users su on su.id = so.signed_by left join users ru on ru.id = so.resolved_by
                where exists (select 1 from attendance_records r where r.site_id = si.id and r.duty_date between ? and ?)""");
        List<Object> args = new ArrayList<>(List.of(ym.atDay(1), ym.atEndOfMonth(), ym.atDay(1), ym.atDay(1), ym.atEndOfMonth()));
        if (u.siteIds() != null) { sql.append(" and si.id = any(?)"); args.add(u.siteIds().toArray(UUID[]::new)); }
        sql.append(" order by (so.status = 'DISPUTED' and so.resolved_at is null) desc nulls last, c.legal_name, si.name");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public record Resolution(String note) {}

    /** Agency answers a dispute (after correcting attendance or explaining). The client can sign off again. */
    @PostMapping("/attendance/signoffs/{id}/resolve")
    @Transactional
    public Map<String, Object> resolve(@PathVariable UUID id, @RequestBody Resolution r) {
        Authz.requireAny(Perm.ATTENDANCE_APPROVE, Perm.BILLING_WRITE);
        if (r == null || r.note() == null || r.note().isBlank()) throw ApiException.field("note", "Explain how it was resolved");
        var rows = jdbc.queryForList("select site_id, status, resolved_at from attendance_signoffs where id = ?", id);
        if (rows.isEmpty()) throw ApiException.notFound("Sign-off");
        Authz.requireSite((UUID) rows.get(0).get("site_id"), "Sign-off");
        if (!"DISPUTED".equals(rows.get(0).get("status")) || rows.get(0).get("resolved_at") != null)
            throw ApiException.invalidState("Only open disputes can be resolved");
        jdbc.update("""
                update attendance_signoffs set resolved_by = ?, resolved_at = now(), resolution_note = ?, updated_at = now(),
                  version = version + 1 where id = ?""", Authz.userId(), r.note(), id);
        audit.record("ATTENDANCE_DISPUTE_RESOLVED", "AttendanceSignoff", id, Map.of("note", r.note()));
        return Map.of("id", id, "resolved", true);
    }
}
