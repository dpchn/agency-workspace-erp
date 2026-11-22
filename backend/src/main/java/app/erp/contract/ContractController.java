package app.erp.contract;

import app.erp.client.ClientRepository;
import app.erp.client.SiteRepository;
import app.erp.common.*;
import app.erp.platform.TenantService;
import app.erp.security.Authz;
import app.erp.security.Perm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Client contracts with versioned, effective-dated rate cards.
 * Amendment = new version (DRAFT) -> submit -> approve (by someone else) -> previous version is end-dated.
 * Approved versions are immutable so closed billing periods keep their historical rates.
 */
@RestController
@RequestMapping("/v1/contracts")
public class ContractController extends CrudSupport<Contract> {
    @Autowired private ContractVersionRepository versions;
    @Autowired private ContractRateRepository rates;
    @Autowired private ClientRepository clients;
    @Autowired private SiteRepository sites;
    @Autowired private TenantService tenantService;

    public ContractController(ContractRepository repo, ObjectMapper mapper, AuditService audit) {
        super(Contract.class, repo, mapper, audit, Perm.CONTRACT_READ, Perm.CONTRACT_WRITE);
    }

    @Override protected Set<String> filterable() { return Set.of("clientId", "status"); }
    @Override protected List<String> searchable() { return List.of("contractNo", "title"); }
    @Override protected Sort defaultSort() { return Sort.by("startDate").descending(); }

    @Override protected void validate(Contract c, boolean isNew) {
        if (c.getClientId() == null || !clients.existsById(c.getClientId())) throw ApiException.field("clientId", "Valid client required");
        if (c.getContractNo() == null || c.getContractNo().isBlank()) throw ApiException.field("contractNo", "Required");
        if (c.getStartDate() == null) throw ApiException.field("startDate", "Required");
        if (c.getEndDate() != null && c.getEndDate().isBefore(c.getStartDate())) throw ApiException.field("endDate", "Before start");
    }

    /** Creating a contract also opens version 1 as a draft. */
    @Override protected void afterSave(Contract c, boolean isNew) {
        if (!isNew) return;
        ContractVersion v = new ContractVersion();
        v.setContractId(c.getId());
        v.setVersionNo(1);
        v.setEffectiveFrom(c.getStartDate());
        v.setEffectiveTo(c.getEndDate());
        v.setGstRatePct(tenantService.settings().decimal("defaultGstRatePct"));
        v.setCreatedBy(Authz.userId());
        versions.saveAndFlush(v);
    }

    @GetMapping("/{id}/versions")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listVersions(@PathVariable UUID id) {
        Authz.require(Perm.CONTRACT_READ);
        load(id);
        return versions.findAll((r, q, cb) -> cb.equal(r.get("contractId"), id), Sort.by("versionNo")).stream()
                .map(v -> Map.<String, Object>of("version", v, "rates", ratesOf(v.getId()))).toList();
    }

    public record AmendRequest(LocalDate effectiveFrom, String notes) {}

    /** Amendment: copies the latest version's rates into a new draft version from a date. */
    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ContractVersion amend(@PathVariable UUID id, @RequestBody AmendRequest req) {
        Authz.require(Perm.CONTRACT_WRITE);
        Contract c = load(id);
        if (req.effectiveFrom() == null) throw ApiException.field("effectiveFrom", "Required");
        List<ContractVersion> all = versions.findAll((r, q, cb) -> cb.equal(r.get("contractId"), id), Sort.by("versionNo"));
        if (all.stream().anyMatch(v -> Set.of("DRAFT", "PENDING_APPROVAL").contains(v.getStatus())))
            throw ApiException.invalidState("Finish or reject the open draft version first");
        ContractVersion latest = all.get(all.size() - 1);
        if (!req.effectiveFrom().isAfter(latest.getEffectiveFrom())) throw ApiException.field("effectiveFrom", "Must be after current version start");
        ContractVersion v = new ContractVersion();
        v.setContractId(id);
        v.setVersionNo(latest.getVersionNo() + 1);
        v.setEffectiveFrom(req.effectiveFrom());
        v.setEffectiveTo(c.getEndDate());
        v.setGstRatePct(latest.getGstRatePct());
        v.setAbsencePenaltyPerDuty(latest.getAbsencePenaltyPerDuty());
        v.setNotes(req.notes());
        v.setCreatedBy(Authz.userId());
        versions.saveAndFlush(v);
        for (ContractRate r : ratesOf(latest.getId())) {
            ContractRate copy = mapper.convertValue(mapper.valueToTree(r), ContractRate.class);
            copy.setId(null);
            copy.setVersion(null);
            copy.setContractVersionId(v.getId());
            rates.save(copy);
        }
        audit.record("CONTRACT_AMEND", "ContractVersion", v.getId(), Map.of("contractId", id, "effectiveFrom", req.effectiveFrom()));
        return v;
    }

    @PatchMapping("/versions/{versionId}")
    @Transactional
    public ContractVersion editVersion(@PathVariable UUID versionId, @RequestBody JsonNode body) throws Exception {
        Authz.require(Perm.CONTRACT_WRITE);
        ContractVersion v = draftVersion(versionId);
        mapper.readerForUpdating(v).readValue(body);
        if (v.getEffectiveTo() != null && v.getEffectiveTo().isBefore(v.getEffectiveFrom())) throw ApiException.field("effectiveTo", "Before start");
        return versions.saveAndFlush(v);
    }

    @PostMapping("/versions/{versionId}/rates")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ContractRate addRate(@PathVariable UUID versionId, @RequestBody ContractRate rate) {
        Authz.require(Perm.CONTRACT_WRITE);
        ContractVersion v = draftVersion(versionId);
        rate.setContractVersionId(v.getId());
        validateRate(v, rate);
        return rates.saveAndFlush(rate);
    }

    @PatchMapping("/rates/{rateId}")
    @Transactional
    public ContractRate editRate(@PathVariable UUID rateId, @RequestBody JsonNode body) throws Exception {
        Authz.require(Perm.CONTRACT_WRITE);
        ContractRate r = rates.findById(rateId).orElseThrow(() -> ApiException.notFound("Rate"));
        ContractVersion v = draftVersion(r.getContractVersionId());
        mapper.readerForUpdating(r).readValue(body);
        r.setContractVersionId(v.getId());
        validateRate(v, r);
        return rates.saveAndFlush(r);
    }

    @DeleteMapping("/rates/{rateId}")
    @Transactional
    public void deleteRate(@PathVariable UUID rateId) {
        Authz.require(Perm.CONTRACT_WRITE);
        ContractRate r = rates.findById(rateId).orElseThrow(() -> ApiException.notFound("Rate"));
        draftVersion(r.getContractVersionId());
        rates.delete(r);
    }

    @PostMapping("/versions/{versionId}/submit")
    @Transactional
    public ContractVersion submit(@PathVariable UUID versionId) {
        Authz.require(Perm.CONTRACT_WRITE);
        ContractVersion v = draftVersion(versionId);
        if (!"DRAFT".equals(v.getStatus())) throw ApiException.invalidState("Only drafts can be submitted");
        if (ratesOf(v.getId()).isEmpty()) throw ApiException.invalidState("Add at least one rate line");
        v.setStatus("PENDING_APPROVAL");
        audit.record("CONTRACT_VERSION_SUBMIT", "ContractVersion", v.getId(), Map.of());
        return v;
    }

    public record Decision(boolean approve, String note) {}

    @PostMapping("/versions/{versionId}/decision")
    @Idempotent
    @Transactional
    public ContractVersion decide(@PathVariable UUID versionId, @RequestBody Decision d) {
        Authz.require(Perm.CONTRACT_APPROVE);
        ContractVersion v = versions.findById(versionId).orElseThrow(() -> ApiException.notFound("Contract version"));
        if (!"PENDING_APPROVAL".equals(v.getStatus())) throw ApiException.invalidState("Version is not pending approval");
        if (tenantService.settings().bool("requireApprovalSeparation") && Objects.equals(v.getCreatedBy(), Authz.userId()))
            throw ApiException.forbidden("Maker cannot approve their own contract version");
        if (!d.approve()) {
            v.setStatus("REJECTED");
            audit.record("CONTRACT_VERSION_REJECT", "ContractVersion", v.getId(), Map.of("note", String.valueOf(d.note())));
            return v;
        }
        // End-date the previous approved version(s) that overlap.
        for (ContractVersion prev : versions.findAll((r, q, cb) -> cb.and(cb.equal(r.get("contractId"), v.getContractId()),
                cb.equal(r.get("status"), "APPROVED")))) {
            if (prev.getEffectiveTo() == null || !prev.getEffectiveTo().isBefore(v.getEffectiveFrom())) {
                prev.setEffectiveTo(v.getEffectiveFrom().minusDays(1));
            }
        }
        v.setStatus("APPROVED");
        v.setApprovedBy(Authz.userId());
        v.setApprovedAt(Instant.now());
        Contract c = repo.findById(v.getContractId()).orElseThrow();
        if ("DRAFT".equals(c.getStatus())) c.setStatus("ACTIVE");
        audit.record("CONTRACT_VERSION_APPROVE", "ContractVersion", v.getId(), Map.of("contractId", c.getId()));
        return v;
    }

    public record StatusChange(String status, String reason) {}

    @PostMapping("/{id}/status")
    @Transactional
    public Contract changeStatus(@PathVariable UUID id, @RequestBody StatusChange s) {
        Authz.require(Perm.CONTRACT_APPROVE);
        Contract c = load(id);
        if (!Set.of("ACTIVE", "SUSPENDED", "TERMINATED", "EXPIRED").contains(s.status())) throw ApiException.field("status", "Invalid");
        c.setStatus(s.status());
        audit.record("CONTRACT_" + s.status(), "Contract", id, Map.of("reason", String.valueOf(s.reason())));
        return c;
    }

    private List<ContractRate> ratesOf(UUID versionId) {
        return rates.findAll((r, q, cb) -> cb.equal(r.get("contractVersionId"), versionId));
    }

    private ContractVersion draftVersion(UUID versionId) {
        ContractVersion v = versions.findById(versionId).orElseThrow(() -> ApiException.notFound("Contract version"));
        if (!"DRAFT".equals(v.getStatus())) throw ApiException.invalidState("Approved/submitted versions are immutable; create an amendment");
        return v;
    }

    private static final Set<String> MODELS = Set.of("MONTHLY_PER_POST", "PER_SHIFT", "PER_DAY", "PER_HOUR", "PER_HEADCOUNT_MONTH", "LUMPSUM_MONTHLY");

    private void validateRate(ContractVersion v, ContractRate r) {
        if (!MODELS.contains(String.valueOf(r.getBillingModel()))) throw ApiException.field("billingModel", "One of " + MODELS);
        if (r.getRate() == null || r.getRate().signum() < 0) throw ApiException.field("rate", "Required");
        if (r.getServiceTypeId() == null) throw ApiException.field("serviceTypeId", "Required");
        var site = sites.findById(r.getSiteId() == null ? new UUID(0, 0) : r.getSiteId()).orElseThrow(() -> ApiException.field("siteId", "Valid site required"));
        Contract c = repo.findById(v.getContractId()).orElseThrow();
        if (!site.getClientId().equals(c.getClientId())) throw ApiException.field("siteId", "Site belongs to another client");
        if (r.getQuantity() == null || r.getQuantity().signum() <= 0) throw ApiException.field("quantity", "Positive");
    }
}
