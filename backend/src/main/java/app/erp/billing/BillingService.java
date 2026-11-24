package app.erp.billing;

import app.erp.client.Client;
import app.erp.client.ClientRepository;
import app.erp.common.*;
import app.erp.contract.*;
import app.erp.platform.Tenant;
import app.erp.platform.TenantService;
import app.erp.platform.TenantSettings;
import app.erp.security.Authz;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoice lifecycle: DRAFT -> REVIEWED -> APPROVED -> ISSUED -> PARTIALLY_PAID -> PAID (or OVERDUE / DISPUTED).
 * Re-generation supersedes drafts; issued invoices are only corrected through credit/debit notes.
 */
@Service
public class BillingService {
    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lines;
    private final ReceiptRepository receipts;
    private final ReceiptAllocationRepository allocations;
    private final ContractRepository contracts;
    private final ContractVersionRepository versions;
    private final ContractRateRepository rates;
    private final ClientRepository clients;
    private final TenantService tenantService;
    private final SequenceService sequences;
    private final AuditService audit;
    private final OutboxService outbox;
    private final JdbcTemplate jdbc;

    public BillingService(InvoiceRepository invoices, InvoiceLineRepository lines, ReceiptRepository receipts,
                          ReceiptAllocationRepository allocations, ContractRepository contracts, ContractVersionRepository versions,
                          ContractRateRepository rates, ClientRepository clients, TenantService tenantService, SequenceService sequences,
                          AuditService audit, OutboxService outbox, JdbcTemplate jdbc) {
        this.invoices = invoices;
        this.lines = lines;
        this.receipts = receipts;
        this.allocations = allocations;
        this.contracts = contracts;
        this.versions = versions;
        this.rates = rates;
        this.clients = clients;
        this.tenantService = tenantService;
        this.sequences = sequences;
        this.audit = audit;
        this.outbox = outbox;
        this.jdbc = jdbc;
    }

    public record GenerateResult(Invoice invoice, List<String> warnings) {}

    @Transactional
    public GenerateResult generate(UUID clientId, YearMonth month) {
        Client client = clients.findById(clientId).orElseThrow(() -> ApiException.notFound("Client"));
        LocalDate ms = month.atDay(1), me = month.atEndOfMonth();
        List<String> warnings = new ArrayList<>();

        List<Invoice> existing = invoices.findAll((r, q, cb) -> cb.and(cb.equal(r.get("clientId"), clientId), cb.equal(r.get("kind"), "INVOICE"),
                cb.equal(r.get("periodStart"), ms), r.get("status").in("DRAFT", "REVIEWED", "APPROVED", "ISSUED", "PARTIALLY_PAID", "PAID", "OVERDUE", "DISPUTED")));
        boolean supplementary = false;
        int versionNo = 1;
        for (Invoice ex : existing) {
            switch (ex.getStatus()) {
                case "DRAFT", "REVIEWED" -> { ex.setStatus("SUPERSEDED"); versionNo = Math.max(versionNo, ex.getVersionNo() + 1); }
                case "APPROVED" -> throw ApiException.invalidState("An approved invoice exists for this period; issue or reject it first");
                default -> supplementary = true;
            }
        }
        invoices.flush();
        if (supplementary) warnings.add("Invoice already issued for this period: generating a supplementary invoice for late-approved duties only");
        warnings.addAll(signoffWarnings(clientId, ms));

        Tenant tenant = tenantService.current();
        Invoice inv = new Invoice();
        inv.setClientId(clientId);
        inv.setPeriodStart(ms);
        inv.setPeriodEnd(me);
        inv.setVersionNo(versionNo);
        inv.setPlaceOfSupply(client.getStateCode());
        inv.setSupplierState(tenant.getStateCode());
        inv.setCreatedBy(Authz.userId());
        inv.setNotes(supplementary ? "Supplementary invoice" : null);
        invoices.saveAndFlush(inv);

        Map<UUID, String> serviceNames = new HashMap<>();
        jdbc.query("select id, name from service_types", rs -> { serviceNames.put((UUID) rs.getObject(1), rs.getString(2)); });
        Map<UUID, String> siteNames = new HashMap<>();
        jdbc.query("select id, name from sites where client_id = ?", rs -> { siteNames.put((UUID) rs.getObject(1), rs.getString(2)); }, clientId);

        BigDecimal gstRate = null;
        BigDecimal subtotal = BigDecimal.ZERO;
        Map<UUID, Integer> pendingBySite = new LinkedHashMap<>();
        if (!me.isBefore(LocalDate.now(tenantService.zone())))
            warnings.add("The month has not ended: days not yet worked count as uncovered duties. Generate after " + me + " for the final invoice");
        for (Contract c : contracts.findAll((r, q, cb) -> cb.and(cb.equal(r.get("clientId"), clientId), r.get("status").in("ACTIVE", "SUSPENDED", "TERMINATED", "EXPIRED")))) {
            if (inv.getContractId() == null) inv.setContractId(c.getId());
            for (ContractVersion v : versions.findAll((r, q, cb) -> cb.and(cb.equal(r.get("contractId"), c.getId()), cb.equal(r.get("status"), "APPROVED")), Sort.by("effectiveFrom"))) {
                LocalDate from = max(v.getEffectiveFrom(), ms, c.getStartDate());
                LocalDate to = min(v.getEffectiveTo(), me, c.getEndDate());
                if (to.isBefore(from)) continue;
                if (gstRate == null) gstRate = v.getGstRatePct();
                for (ContractRate rate : rates.findAll((r, q, cb) -> cb.equal(r.get("contractVersionId"), v.getId()))) {
                    List<InvoiceEngine.Duty> duties = jdbc.query("""
                            select id, duty_date, bill_units, worked_minutes, ot_minutes, is_holiday, shift_kind, employee_id
                            from attendance_records where client_id = ? and site_id = ? and service_type_id = ?
                              and duty_date between ? and ? and approval_status = 'APPROVED' and billable and bill_units > 0
                              and invoice_id is null""",
                            (rs, i) -> new InvoiceEngine.Duty((UUID) rs.getObject(1), rs.getDate(2).toLocalDate(), rs.getBigDecimal(3),
                                    rs.getInt(4), rs.getInt(5), rs.getBoolean(6), rs.getString(7), (UUID) rs.getObject(8)),
                            clientId, rate.getSiteId(), rate.getServiceTypeId(), from, to);
                    BigDecimal expected = jdbc.queryForObject("""
                            select coalesce(sum(required_headcount), 0) from shifts
                            where site_id = ? and service_type_id = ? and duty_date between ? and ? and kind = 'REGULAR' and status <> 'CANCELLED'""",
                            BigDecimal.class, rate.getSiteId(), rate.getServiceTypeId(), from, to);
                    Integer pending = jdbc.queryForObject("""
                            select count(*) from attendance_records where client_id = ? and site_id = ? and service_type_id = ?
                              and duty_date between ? and ? and approval_status = 'PENDING'""", Integer.class,
                            clientId, rate.getSiteId(), rate.getServiceTypeId(), from, to);
                    if (pending != null && pending > 0) pendingBySite.merge(rate.getSiteId(), pending, Integer::sum);
                    String label = (rate.getDescription() != null ? rate.getDescription() : serviceNames.getOrDefault(rate.getServiceTypeId(), "Service"))
                            + " @ " + siteNames.getOrDefault(rate.getSiteId(), "site");
                    var out = InvoiceEngine.bill(new InvoiceEngine.Input(rate, label, from, to, month, expected, duties,
                            v.getAbsencePenaltyPerDuty(), supplementary));
                    for (var l : out) {
                        InvoiceLine il = new InvoiceLine();
                        il.setInvoiceId(inv.getId());
                        il.setSiteId(rate.getSiteId());
                        il.setServiceTypeId(rate.getServiceTypeId());
                        il.setContractRateId(rate.getId());
                        il.setLineType(l.type());
                        il.setDescription(l.description());
                        il.setQuantity(l.quantity());
                        il.setUnit(l.unit());
                        il.setRate(l.rate());
                        il.setAmount(l.amount());
                        il.setSourceRef(Map.of("recordIds", l.recordIds().stream().map(UUID::toString).toList(),
                                "contractVersionId", v.getId().toString(), "billingModel", rate.getBillingModel()));
                        lines.save(il);
                        subtotal = subtotal.add(l.amount());
                    }
                }
            }
        }
        pendingBySite.forEach((site, n) -> warnings.add(n + " pending attendance record(s) at " + siteNames.get(site) + " not billed"));
        if (subtotal.signum() == 0 && lines.findAll((r, q, cb) -> cb.equal(r.get("invoiceId"), inv.getId())).isEmpty())
            warnings.add("Nothing to bill: no approved contract version or no billable duties in this period");
        TenantSettings st = tenantService.settings();
        inv.setGstRatePct(gstRate != null ? gstRate : st.decimal("defaultGstRatePct"));
        applyTotals(inv, subtotal);
        audit.record("INVOICE_GENERATE", "Invoice", inv.getId(), Map.of("clientId", clientId, "month", month.toString(), "version", versionNo));
        return new GenerateResult(inv, warnings);
    }

    private void applyTotals(Invoice inv, BigDecimal subtotal) {
        inv.setSubtotal(Money.round(subtotal));
        var tax = InvoiceEngine.gst(inv.getSubtotal(), inv.getGstRatePct(), inv.getSupplierState(), inv.getPlaceOfSupply());
        inv.setCgst(tax.cgst());
        inv.setSgst(tax.sgst());
        inv.setIgst(tax.igst());
        inv.setTotal(inv.getSubtotal().add(tax.cgst()).add(tax.sgst()).add(tax.igst()));
    }

    public record ManualLine(String description, BigDecimal quantity, String unit, BigDecimal rate, UUID siteId) {}

    @Transactional
    public Invoice addManualLine(UUID invoiceId, ManualLine m) {
        Invoice inv = get(invoiceId);
        if (!"DRAFT".equals(inv.getStatus())) throw ApiException.invalidState("Only drafts can be edited");
        if (m.description() == null || m.quantity() == null || m.rate() == null) throw ApiException.badRequest("description, quantity, rate required");
        InvoiceLine il = new InvoiceLine();
        il.setInvoiceId(invoiceId);
        il.setSiteId(m.siteId());
        il.setLineType("MANUAL");
        il.setDescription(m.description());
        il.setQuantity(m.quantity());
        il.setUnit(m.unit());
        il.setRate(m.rate());
        il.setAmount(Money.round(m.quantity().multiply(m.rate())));
        lines.saveAndFlush(il);
        recalcTotals(inv);
        return inv;
    }

    @Transactional
    public Invoice removeLine(UUID invoiceId, UUID lineId) {
        Invoice inv = get(invoiceId);
        if (!"DRAFT".equals(inv.getStatus())) throw ApiException.invalidState("Only drafts can be edited");
        InvoiceLine l = lines.findById(lineId).filter(x -> x.getInvoiceId().equals(invoiceId)).orElseThrow(() -> ApiException.notFound("Line"));
        lines.delete(l);
        lines.flush();
        recalcTotals(inv);
        return inv;
    }

    private void recalcTotals(Invoice inv) {
        BigDecimal sub = lines.findAll((r, q, cb) -> cb.equal(r.get("invoiceId"), inv.getId())).stream()
                .map(InvoiceLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        applyTotals(inv, sub);
    }

    @Transactional
    public Invoice transition(UUID id, String action, String reason) {
        Invoice inv = get(id);
        UUID me = Authz.userId();
        switch (action) {
            case "review" -> { need(inv, "DRAFT"); inv.setStatus("REVIEWED"); }
            case "approve" -> {
                need(inv, "REVIEWED");
                if (tenantService.settings().bool("requireApprovalSeparation") && me.equals(inv.getCreatedBy()))
                    throw ApiException.forbidden("The person who prepared the invoice cannot approve it");
                inv.setStatus("APPROVED");
                inv.setApprovedBy(me);
            }
            case "reject" -> {
                if (!Set.of("REVIEWED", "APPROVED").contains(inv.getStatus())) throw ApiException.invalidState("Nothing to reject");
                inv.setStatus("DRAFT");
            }
            case "issue" -> { need(inv, "APPROVED"); issue(inv); }
            case "cancel" -> {
                if (!Set.of("DRAFT", "REVIEWED", "APPROVED").contains(inv.getStatus()))
                    throw ApiException.invalidState("Issued invoices cannot be cancelled; raise a credit note");
                inv.setStatus("CANCELLED");
            }
            case "dispute" -> {
                if (!Set.of("ISSUED", "PARTIALLY_PAID", "OVERDUE").contains(inv.getStatus())) throw ApiException.invalidState("Cannot dispute");
                if (reason == null || reason.isBlank()) throw ApiException.field("reason", "Required");
                inv.setStatus("DISPUTED");
                inv.setDisputeReason(reason);
            }
            case "resolve-dispute" -> { need(inv, "DISPUTED"); refreshPaymentStatus(inv); }
            default -> throw ApiException.badRequest("Unknown action");
        }
        audit.record("INVOICE_" + action.toUpperCase().replace('-', '_'), "Invoice", id, Map.of("status", inv.getStatus()));
        return inv;
    }

    /** Client sign-off of attendance per site for the month: disputes and missing confirmations. */
    List<String> signoffWarnings(UUID clientId, LocalDate month) {
        List<String> out = new ArrayList<>();
        if (!tenantService.settings().bool("clientSignoffEnabled")) return out;
        jdbc.query("""
                select si.name, so.status, so.resolved_at is not null as resolved
                from sites si left join attendance_signoffs so on so.site_id = si.id and so.period_month = ?
                where si.client_id = ? and exists (select 1 from attendance_records r where r.site_id = si.id
                      and r.duty_date between ? and ?)
                order by si.name""", rs -> {
            String st = rs.getString(2);
            if (st == null) out.add("Client has not confirmed attendance for " + rs.getString(1));
            else if ("DISPUTED".equals(st) && !rs.getBoolean(3)) out.add("Client DISPUTED attendance for " + rs.getString(1) + "; resolve before issuing");
        }, month, clientId, month, month.withDayOfMonth(month.lengthOfMonth()));
        return out;
    }

    private void issue(Invoice inv) {
        Tenant t = tenantService.current();
        TenantSettings st = tenantService.settings();
        if ("INVOICE".equals(inv.getKind()) && st.bool("clientSignoffEnabled") && st.bool("blockInvoiceOnDispute")) {
            Long open = jdbc.queryForObject("""
                    select count(*) from attendance_signoffs where client_id = ? and period_month = ?
                      and status = 'DISPUTED' and resolved_at is null""", Long.class, inv.getClientId(), inv.getPeriodStart().withDayOfMonth(1));
            if (open != null && open > 0)
                throw ApiException.invalidState("The client disputed this month's attendance. Resolve the dispute (Attendance → Client sign-off) before issuing");
        }
        LocalDate today = LocalDate.now(tenantService.zone());
        int fyStart = t.getFiscalYearStartMonth() == null ? 4 : t.getFiscalYearStartMonth();
        int fy = today.getMonthValue() >= fyStart ? today.getYear() : today.getYear() - 1;
        String fyLabel = fyStart == 1 ? String.valueOf(fy) : fy + "-" + String.valueOf(fy + 1).substring(2);
        String prefix = "CREDIT_NOTE".equals(inv.getKind()) ? st.str("creditNotePrefix") : st.str("invoicePrefix");
        long seq = sequences.next(prefix + "/" + fyLabel);
        inv.setInvoiceNo(prefix + "/" + fyLabel + "/" + String.format("%05d", seq));
        inv.setIssueDate(today);
        Client c = clients.findById(inv.getClientId()).orElseThrow();
        int terms = c.getPaymentTermsDays() == null ? 30 : c.getPaymentTermsDays();
        inv.setDueDate(today.plusDays(terms));
        inv.setStatus("ISSUED");
        // lock billed duties so they can never be billed twice
        List<UUID> ids = new ArrayList<>();
        for (InvoiceLine l : lines.findAll((r, q, cb) -> cb.equal(r.get("invoiceId"), inv.getId()))) {
            Object rec = l.getSourceRef() == null ? null : l.getSourceRef().get("recordIds");
            if (rec instanceof List<?> list) list.forEach(x -> ids.add(UUID.fromString(x.toString())));
        }
        if (!ids.isEmpty()) {
            int n = jdbc.update("update attendance_records set invoice_id = ?, updated_at = now() where id = any(?) and invoice_id is null",
                    inv.getId(), ids.toArray(UUID[]::new));
            if (n != ids.size()) throw ApiException.conflict("Some duties were billed by another invoice; regenerate");
        }
        if ("CREDIT_NOTE".equals(inv.getKind()) && inv.getRefInvoiceId() != null) {
            Invoice orig = get(inv.getRefInvoiceId());
            orig.setAmountPaid(orig.getAmountPaid().add(inv.getTotal()));
            refreshPaymentStatus(orig);
            inv.setAmountPaid(inv.getTotal());
            inv.setStatus("PAID");
        }
        outbox.publish(OutboxService.INVOICE_ISSUED, inv.getId(), Map.of("invoiceNo", inv.getInvoiceNo(), "total", inv.getTotal().toPlainString()));
    }

    public record CreditNoteRequest(BigDecimal amount, String reason) {}

    /** Correction of an issued invoice without editing it. Created as DRAFT; follows the normal approval flow. */
    @Transactional
    public Invoice creditNote(UUID invoiceId, CreditNoteRequest req) {
        Invoice orig = get(invoiceId);
        if (!Set.of("ISSUED", "PARTIALLY_PAID", "PAID", "OVERDUE", "DISPUTED").contains(orig.getStatus()))
            throw ApiException.invalidState("Credit notes apply to issued invoices");
        if (req.amount() == null || req.amount().signum() <= 0) throw ApiException.field("amount", "Positive taxable amount required");
        if (req.reason() == null || req.reason().isBlank()) throw ApiException.field("reason", "Required");
        BigDecimal credited = jdbc.queryForObject("select coalesce(sum(subtotal),0) from invoices where ref_invoice_id = ? and kind = 'CREDIT_NOTE' and status <> 'CANCELLED'",
                BigDecimal.class, invoiceId);
        if (credited.add(req.amount()).compareTo(orig.getSubtotal()) > 0) throw ApiException.field("amount", "Exceeds invoice value");
        Invoice cn = new Invoice();
        cn.setKind("CREDIT_NOTE");
        cn.setRefInvoiceId(invoiceId);
        cn.setClientId(orig.getClientId());
        cn.setContractId(orig.getContractId());
        cn.setPeriodStart(orig.getPeriodStart());
        cn.setPeriodEnd(orig.getPeriodEnd());
        cn.setPlaceOfSupply(orig.getPlaceOfSupply());
        cn.setSupplierState(orig.getSupplierState());
        cn.setGstRatePct(orig.getGstRatePct());
        cn.setNotes("Against " + orig.getInvoiceNo() + ": " + req.reason());
        cn.setCreatedBy(Authz.userId());
        invoices.saveAndFlush(cn);
        InvoiceLine l = new InvoiceLine();
        l.setInvoiceId(cn.getId());
        l.setLineType("MANUAL");
        l.setDescription("Credit against " + orig.getInvoiceNo() + " - " + req.reason());
        l.setQuantity(BigDecimal.ONE);
        l.setRate(req.amount());
        l.setAmount(Money.round(req.amount()));
        lines.saveAndFlush(l);
        applyTotals(cn, l.getAmount());
        audit.record("CREDIT_NOTE_CREATE", "Invoice", cn.getId(), Map.of("against", invoiceId));
        return cn;
    }

    // ------------------------------------------------------------------ receipts

    public record Allocation(UUID invoiceId, BigDecimal amount) {}

    public record ReceiptRequest(UUID clientId, BigDecimal amount, BigDecimal tdsAmount, LocalDate receivedOn, String mode,
                                 String externalRef, String notes, List<Allocation> allocations) {}

    /** Records a payment (idempotent on externalRef) and allocates it explicitly or oldest-due-first. */
    @Transactional
    public Receipt recordReceipt(ReceiptRequest req) {
        if (req.clientId() == null || !clients.existsById(req.clientId())) throw ApiException.field("clientId", "Valid client required");
        if (req.amount() == null || req.amount().signum() <= 0) throw ApiException.field("amount", "Positive amount required");
        if (req.externalRef() == null || req.externalRef().isBlank()) throw ApiException.field("externalRef", "Bank/UTR reference required");
        var dup = receipts.findOne((r, q, cb) -> cb.equal(r.get("externalRef"), req.externalRef()));
        if (dup.isPresent()) {
            if (dup.get().getAmount().compareTo(req.amount()) != 0) throw ApiException.conflict("Reference already used for a different amount");
            return dup.get();
        }
        BigDecimal tds = req.tdsAmount() == null ? BigDecimal.ZERO : req.tdsAmount();
        Receipt rc = new Receipt();
        rc.setClientId(req.clientId());
        rc.setAmount(req.amount());
        rc.setTdsAmount(tds);
        rc.setReceivedOn(req.receivedOn() == null ? LocalDate.now() : req.receivedOn());
        rc.setMode(req.mode() == null ? "BANK_TRANSFER" : req.mode());
        rc.setExternalRef(req.externalRef());
        rc.setNotes(req.notes());
        rc.setCreatedBy(Authz.userId());
        receipts.saveAndFlush(rc);

        BigDecimal available = req.amount().add(tds);
        List<Allocation> plan = new ArrayList<>();
        if (req.allocations() != null && !req.allocations().isEmpty()) {
            plan.addAll(req.allocations());
        } else {
            for (Invoice inv : invoices.findAll((r, q, cb) -> cb.and(cb.equal(r.get("clientId"), req.clientId()), cb.equal(r.get("kind"), "INVOICE"),
                    r.get("status").in("ISSUED", "PARTIALLY_PAID", "OVERDUE")), Sort.by("dueDate").and(Sort.by("issueDate")))) {
                if (available.signum() <= 0) break;
                BigDecimal take = inv.balance().min(available);
                plan.add(new Allocation(inv.getId(), take));
                available = available.subtract(take);
            }
            available = req.amount().add(tds);
        }
        for (Allocation a : plan) {
            if (a.amount() == null || a.amount().signum() <= 0) continue;
            Invoice inv = get(a.invoiceId());
            if (!inv.getClientId().equals(req.clientId())) throw ApiException.field("allocations", "Invoice belongs to another client");
            if (!Set.of("ISSUED", "PARTIALLY_PAID", "OVERDUE", "DISPUTED").contains(inv.getStatus())) throw ApiException.field("allocations", "Invoice not payable");
            if (a.amount().compareTo(inv.balance()) > 0) throw ApiException.field("allocations", "Allocation exceeds balance of " + inv.getInvoiceNo());
            if (a.amount().compareTo(available) > 0) throw ApiException.field("allocations", "Allocations exceed receipt amount");
            ReceiptAllocation ra = new ReceiptAllocation();
            ra.setReceiptId(rc.getId());
            ra.setInvoiceId(inv.getId());
            ra.setAmount(a.amount());
            allocations.save(ra);
            inv.setAmountPaid(inv.getAmountPaid().add(a.amount()));
            refreshPaymentStatus(inv);
            available = available.subtract(a.amount());
        }
        rc.setUnallocated(available.max(BigDecimal.ZERO));
        outbox.publish(OutboxService.PAYMENT_RECORDED, rc.getId(), Map.of("amount", req.amount().toPlainString()));
        audit.record("RECEIPT", "Receipt", rc.getId(), Map.of("clientId", req.clientId(), "amount", req.amount()));
        return rc;
    }

    void refreshPaymentStatus(Invoice inv) {
        if ("DISPUTED".equals(inv.getStatus())) return;
        if (inv.balance().signum() <= 0) inv.setStatus("PAID");
        else if (inv.getAmountPaid().signum() > 0) inv.setStatus("PARTIALLY_PAID");
        else if (inv.getDueDate() != null && inv.getDueDate().isBefore(LocalDate.now())) inv.setStatus("OVERDUE");
        else inv.setStatus("ISSUED");
    }

    @Transactional
    public int markOverdue() {
        return jdbc.update("update invoices set status = 'OVERDUE', updated_at = now() where status in ('ISSUED','PARTIALLY_PAID') " +
                "and due_date < current_date and total > amount_paid and amount_paid = 0");
    }

    public Invoice get(UUID id) {
        Invoice inv = invoices.findById(id).orElseThrow(() -> ApiException.notFound("Invoice"));
        var u = Authz.user();
        if (u.isClientUser() && (!inv.getClientId().equals(u.clientId()) || Set.of("DRAFT", "REVIEWED", "APPROVED", "SUPERSEDED", "CANCELLED").contains(inv.getStatus())))
            throw ApiException.notFound("Invoice");
        return inv;
    }

    private static void need(Invoice inv, String status) {
        if (!status.equals(inv.getStatus())) throw ApiException.invalidState("Invoice is " + inv.getStatus() + ", expected " + status);
    }

    private static LocalDate max(LocalDate... ds) {
        return Arrays.stream(ds).filter(Objects::nonNull).max(LocalDate::compareTo).orElseThrow();
    }

    private static LocalDate min(LocalDate... ds) {
        return Arrays.stream(ds).filter(Objects::nonNull).min(LocalDate::compareTo).orElseThrow();
    }
}
