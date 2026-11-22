package app.erp.billing;

import app.erp.common.ApiException;
import app.erp.common.Idempotent;
import app.erp.platform.TenantService;
import app.erp.security.Authz;
import app.erp.security.Perm;
import java.time.YearMonth;
import java.util.*;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

@RestController
@RequestMapping("/v1")
public class BillingController {
    private final BillingService service;
    private final InvoiceRepository invoices;
    private final ReceiptRepository receipts;
    private final TenantService tenantService;
    private final JdbcTemplate jdbc;

    public BillingController(BillingService service, InvoiceRepository invoices, ReceiptRepository receipts,
                             TenantService tenantService, JdbcTemplate jdbc) {
        this.service = service;
        this.invoices = invoices;
        this.receipts = receipts;
        this.tenantService = tenantService;
        this.jdbc = jdbc;
    }

    public record GenerateRequest(UUID clientId, String month) {}

    @PostMapping("/invoices/generate")
    @Idempotent
    public BillingService.GenerateResult generate(@RequestBody GenerateRequest r) {
        Authz.require(Perm.BILLING_WRITE);
        if (r.clientId() == null || r.month() == null) throw ApiException.badRequest("clientId and month (YYYY-MM) required");
        return service.generate(r.clientId(), YearMonth.parse(r.month()));
    }

    @GetMapping("/invoices")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(@RequestParam(required = false) UUID clientId, @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String month) {
        var u = Authz.user();
        Authz.requireAny(Perm.BILLING_READ, Perm.CLIENT_PORTAL);
        StringBuilder sql = new StringBuilder("""
                select i.id, i.invoice_no, i.kind, i.status, i.version_no, i.period_start, i.period_end, i.issue_date, i.due_date,
                       i.subtotal, i.cgst, i.sgst, i.igst, i.total, i.amount_paid, i.total - i.amount_paid as balance,
                       i.client_id, c.legal_name as client_name, i.ref_invoice_id
                from invoices i join clients c on c.id = i.client_id where i.status <> 'SUPERSEDED'""");
        List<Object> args = new ArrayList<>();
        if (u.isClientUser()) {
            sql.append(" and i.client_id = ? and i.status not in ('DRAFT','REVIEWED','APPROVED','CANCELLED')");
            args.add(u.clientId());
        }
        if (clientId != null) { sql.append(" and i.client_id = ?"); args.add(clientId); }
        if (status != null) { sql.append(" and i.status = ?"); args.add(status); }
        if (month != null) { sql.append(" and i.period_start = ?"); args.add(YearMonth.parse(month).atDay(1)); }
        sql.append(" order by i.period_start desc, c.legal_name, i.created_at desc limit 1000");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @GetMapping("/invoices/{id}")
    @Transactional(readOnly = true)
    public Map<String, Object> get(@PathVariable UUID id) {
        Authz.requireAny(Perm.BILLING_READ, Perm.CLIENT_PORTAL);
        Invoice inv = service.get(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("invoice", inv);
        out.put("client", jdbc.queryForMap("select id, legal_name, gstin, billing_address, state_code from clients where id = ?", inv.getClientId()));
        out.put("lines", jdbc.queryForList("""
                select l.id, l.line_type, l.description, l.quantity, l.unit, l.rate, l.amount, l.site_id, s.name as site_name
                from invoice_lines l left join sites s on s.id = l.site_id where l.invoice_id = ? order by s.name nulls last, l.line_type""", id));
        out.put("payments", jdbc.queryForList("""
                select r.id, r.external_ref, r.received_on, a.amount from receipt_allocations a join receipts r on r.id = a.receipt_id
                where a.invoice_id = ? order by r.received_on""", id));
        return out;
    }

    public record ActionRequest(String reason) {}

    @PostMapping("/invoices/{id}/{action:review|approve|reject|issue|cancel|dispute|resolve-dispute}")
    @Idempotent
    public Invoice transition(@PathVariable UUID id, @PathVariable String action, @RequestBody(required = false) ActionRequest r) {
        Authz.require(Set.of("approve", "issue", "reject").contains(action) ? Perm.BILLING_APPROVE : Perm.BILLING_WRITE);
        return service.transition(id, action, r == null ? null : r.reason());
    }

    @PostMapping("/invoices/{id}/lines")
    public Invoice addLine(@PathVariable UUID id, @RequestBody BillingService.ManualLine line) {
        Authz.require(Perm.BILLING_WRITE);
        return service.addManualLine(id, line);
    }

    @DeleteMapping("/invoices/{id}/lines/{lineId}")
    public Invoice removeLine(@PathVariable UUID id, @PathVariable UUID lineId) {
        Authz.require(Perm.BILLING_WRITE);
        return service.removeLine(id, lineId);
    }

    @PostMapping("/invoices/{id}/credit-notes")
    @Idempotent
    public Invoice creditNote(@PathVariable UUID id, @RequestBody BillingService.CreditNoteRequest r) {
        Authz.require(Perm.BILLING_WRITE);
        return service.creditNote(id, r);
    }

    /** Printable tax invoice (use the browser's Save-as-PDF). */
    @GetMapping(value = "/invoices/{id}/print", produces = MediaType.TEXT_HTML_VALUE)
    @Transactional(readOnly = true)
    public String print(@PathVariable UUID id) {
        Map<String, Object> d = get(id);
        Invoice inv = (Invoice) d.get("invoice");
        @SuppressWarnings("unchecked") Map<String, Object> c = (Map<String, Object>) d.get("client");
        @SuppressWarnings("unchecked") List<Map<String, Object>> ls = (List<Map<String, Object>>) d.get("lines");
        var t = tenantService.current();
        String color = String.valueOf(t.getBranding().getOrDefault("primaryColor", "#1d4ed8")).replaceAll("[^#0-9a-fA-F]", "");
        StringBuilder rows = new StringBuilder();
        int i = 1;
        for (var l : ls) {
            rows.append("<tr><td>").append(i++).append("</td><td>").append(e(l.get("description"))).append("</td><td class=r>")
                    .append(e(l.get("quantity"))).append(' ').append(e(l.get("unit") == null ? "" : l.get("unit"))).append("</td><td class=r>")
                    .append(e(l.get("rate"))).append("</td><td class=r>").append(e(l.get("amount"))).append("</td></tr>");
        }
        String title = "CREDIT_NOTE".equals(inv.getKind()) ? "Credit Note" : "Tax Invoice";
        return """
                <!doctype html><html><head><meta charset=utf-8><title>%s %s</title><style>
                body{font-family:system-ui,Arial,sans-serif;margin:32px;color:#111}h1{color:%s;margin:0}table{width:100%%;border-collapse:collapse;margin-top:16px}
                td,th{border:1px solid #ddd;padding:6px;font-size:13px;text-align:left}.r{text-align:right}.muted{color:#666;font-size:12px}
                .grid{display:flex;justify-content:space-between;gap:24px;margin-top:16px}@media print{button{display:none}}</style></head><body>
                <button onclick="print()">Print / Save PDF</button>
                <div class=grid><div><h1>%s</h1><div class=muted>%s<br>GSTIN: %s</div></div>
                <div class=r><h2>%s</h2>No: <b>%s</b><br>Date: %s<br>Due: %s<br>Period: %s to %s</div></div>
                <div class=grid><div><b>Bill to</b><br>%s<br>%s<br>GSTIN: %s</div><div class=muted>Place of supply: %s</div></div>
                <table><tr><th>#</th><th>Description</th><th class=r>Qty</th><th class=r>Rate</th><th class=r>Amount</th></tr>%s
                <tr><td colspan=4 class=r>Taxable value</td><td class=r>%s</td></tr>
                <tr><td colspan=4 class=r>CGST</td><td class=r>%s</td></tr><tr><td colspan=4 class=r>SGST</td><td class=r>%s</td></tr>
                <tr><td colspan=4 class=r>IGST</td><td class=r>%s</td></tr><tr><th colspan=4 class=r>Total (%s)</th><th class=r>%s</th></tr></table>
                <p class=muted>%s</p></body></html>""".formatted(
                title, e(inv.getInvoiceNo()), color, e(t.getLegalName() != null ? t.getLegalName() : t.getName()), e(t.getGstin()),
                title, e(inv.getInvoiceNo() == null ? "DRAFT" : inv.getInvoiceNo()), e(inv.getIssueDate()), e(inv.getDueDate()),
                e(inv.getPeriodStart()), e(inv.getPeriodEnd()), e(c.get("legal_name")), e(c.get("billing_address")), e(c.get("gstin")),
                e(inv.getPlaceOfSupply()), rows, inv.getSubtotal(), inv.getCgst(), inv.getSgst(), inv.getIgst(), e(t.getCurrency()),
                inv.getTotal(), e(inv.getNotes()));
    }

    private static String e(Object o) { return o == null ? "" : HtmlUtils.htmlEscape(String.valueOf(o)); }

    // ---------------- receipts
    @PostMapping("/receipts")
    @Idempotent
    public Receipt receipt(@RequestBody BillingService.ReceiptRequest r) {
        Authz.require(Perm.RECEIPT_WRITE);
        return service.recordReceipt(r);
    }

    @GetMapping("/receipts")
    @Transactional(readOnly = true)
    public List<Receipt> receipts(@RequestParam(required = false) UUID clientId) {
        Authz.require(Perm.BILLING_READ);
        return receipts.findAll((r, q, cb) -> clientId == null ? cb.conjunction() : cb.equal(r.get("clientId"), clientId),
                Sort.by("receivedOn").descending());
    }

    /** Receivables aging by client. */
    @GetMapping("/reports/aging")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> aging() {
        Authz.require(Perm.BILLING_READ);
        return jdbc.queryForList("""
                select c.id as client_id, c.legal_name as client_name,
                  sum(case when i.due_date >= current_date then i.total - i.amount_paid else 0 end) as not_due,
                  sum(case when current_date - i.due_date between 1 and 30 then i.total - i.amount_paid else 0 end) as d1_30,
                  sum(case when current_date - i.due_date between 31 and 60 then i.total - i.amount_paid else 0 end) as d31_60,
                  sum(case when current_date - i.due_date between 61 and 90 then i.total - i.amount_paid else 0 end) as d61_90,
                  sum(case when current_date - i.due_date > 90 then i.total - i.amount_paid else 0 end) as d90_plus,
                  sum(i.total - i.amount_paid) as total_outstanding
                from invoices i join clients c on c.id = i.client_id
                where i.kind = 'INVOICE' and i.status in ('ISSUED','PARTIALLY_PAID','OVERDUE','DISPUTED')
                group by c.id, c.legal_name order by total_outstanding desc""");
    }

    /** Client statement: invoices, credit notes and receipts in date order. */
    @GetMapping("/clients/{clientId}/statement")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> statement(@PathVariable UUID clientId) {
        var u = Authz.user();
        if (u.isClientUser() && !clientId.equals(u.clientId())) throw ApiException.notFound("Client");
        Authz.requireAny(Perm.BILLING_READ, Perm.CLIENT_PORTAL);
        return jdbc.queryForList("""
                select * from (
                  select issue_date as date, kind as type, invoice_no as ref, case when kind = 'CREDIT_NOTE' then 0 else total end as debit,
                         case when kind = 'CREDIT_NOTE' then total else 0 end as credit
                  from invoices where client_id = ? and invoice_no is not null and status <> 'CANCELLED'
                  union all
                  select received_on, 'RECEIPT', external_ref, 0, amount + tds_amount from receipts where client_id = ?
                ) x order by date""", clientId, clientId);
    }
}
