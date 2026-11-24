package app.erp.platform;

import app.erp.common.ApiException;
import app.erp.common.AuditService;
import app.erp.common.TenantContext;
import app.erp.security.Authz;
import app.erp.security.Perm;
import app.erp.security.Role;
import app.erp.security.TokenRevocationValidator;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Invite, change role/scope, deactivate and terminate sessions of tenant members. */
@RestController
@RequestMapping("/v1/users")
public class UserAdminController {
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final TokenRevocationValidator revocation;
    @org.springframework.beans.factory.annotation.Autowired private TenantRepository tenants;

    public UserAdminController(UserRepository users, MembershipRepository memberships, PasswordEncoder encoder,
                               AuditService audit, TokenRevocationValidator revocation) {
        this.users = users;
        this.memberships = memberships;
        this.encoder = encoder;
        this.audit = audit;
        this.revocation = revocation;
    }

    public record MemberView(UUID membershipId, UUID userId, String name, String email, String phone, Role role, String status,
                             UUID[] branchIds, UUID[] siteIds, UUID clientId, UUID employeeId, boolean appPhoneBound) {}

    @GetMapping
    @Transactional(readOnly = true)
    public List<MemberView> list() {
        Authz.require(Perm.USER_MANAGE);
        return memberships.findByTenantId(TenantContext.requireTenant()).stream().map(this::view).toList();
    }

    public record InviteRequest(String fullName, String email, String phone, Role role, String password,
                                UUID[] branchIds, UUID[] siteIds, UUID clientId, UUID employeeId) {}

    @PostMapping
    @Transactional
    public Map<String, Object> invite(@RequestBody InviteRequest r) {
        Authz.require(Perm.USER_MANAGE);
        UUID tenant = TenantContext.requireTenant();
        if (r.role() == null) throw ApiException.field("role", "Required");
        if (r.role() == Role.OWNER && Authz.user().role() != Role.OWNER) throw ApiException.forbidden("Only owners can add owners");
        if ((r.role() == Role.CLIENT_ADMIN || r.role() == Role.CLIENT_VIEWER) && r.clientId() == null)
            throw ApiException.field("clientId", "Client users must be linked to a client");
        if (r.role() == Role.GUARD && r.employeeId() == null) throw ApiException.field("employeeId", "Guard users must be linked to an employee");
        if (r.role() == Role.SITE_KIOSK && (r.siteIds() == null || r.siteIds().length == 0))
            throw ApiException.field("siteIds", "A site device must be limited to its site(s)");
        if (PlatformController.STAFF_ROLES.contains(r.role().name())) {
            Integer max = tenants.findById(tenant).map(Tenant::getMaxUsers).orElse(null);
            long staff = memberships.findByTenantId(tenant).stream()
                    .filter(x -> "ACTIVE".equals(x.getStatus()) && PlatformController.STAFF_ROLES.contains(x.getRole().name())).count();
            if (max != null && staff >= max)
                throw ApiException.invalidState("Your plan allows " + max + " office users (guards and clients are unlimited). Ask us to upgrade");
        }
        if ((r.email() == null || r.email().isBlank()) && (r.phone() == null || r.phone().isBlank()))
            throw ApiException.field("email", "E-mail or phone required");

        User u = r.email() != null && !r.email().isBlank() ? users.findByEmailIgnoreCase(r.email().trim()).orElse(null)
                : users.findByPhone(r.phone().trim()).orElse(null);
        String tempPassword = null;
        if (u == null) {
            u = new User();
            u.setEmail(r.email() == null || r.email().isBlank() ? null : r.email().trim().toLowerCase());
            u.setPhone(r.phone() == null || r.phone().isBlank() ? null : r.phone().trim());
            u.setFullName(r.fullName() == null ? "User" : r.fullName());
            tempPassword = r.password() != null && r.password().length() >= 8 ? r.password() : randomPassword();
            u.setPasswordHash(encoder.encode(tempPassword));
            users.saveAndFlush(u);
        }
        if (memberships.findByTenantIdAndUserId(tenant, u.getId()).isPresent()) throw ApiException.conflict("User is already a member");
        Membership m = new Membership();
        m.setTenantId(tenant);
        m.setUserId(u.getId());
        m.setRole(r.role());
        m.setBranchIds(r.branchIds());
        m.setSiteIds(r.siteIds());
        m.setClientId(r.clientId());
        m.setEmployeeId(r.employeeId());
        memberships.saveAndFlush(m);
        audit.record("USER_INVITE", "Membership", m.getId(), Map.of("role", r.role().name()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("member", view(m));
        if (tempPassword != null && r.password() == null) out.put("temporaryPassword", tempPassword); // shown once
        return out;
    }

    public record UpdateRequest(Role role, String status, UUID[] branchIds, UUID[] siteIds, Boolean clearScope,
                                Boolean clearBranchScope, Boolean clearSiteScope) {}

    @PatchMapping("/{membershipId}")
    @Transactional
    public MemberView update(@PathVariable UUID membershipId, @RequestBody UpdateRequest r) {
        Authz.require(Perm.USER_MANAGE);
        Membership m = find(membershipId);
        if (m.getUserId().equals(Authz.userId()) && r.status() != null) throw ApiException.invalidState("You cannot deactivate yourself");
        if (r.role() != null) {
            if ((r.role() == Role.OWNER || m.getRole() == Role.OWNER) && Authz.user().role() != Role.OWNER)
                throw ApiException.forbidden("Only owners can change owners");
            m.setRole(r.role());
        }
        if (r.status() != null) m.setStatus(r.status());
        if (Boolean.TRUE.equals(r.clearScope()) || Boolean.TRUE.equals(r.clearBranchScope())) m.setBranchIds(null);
        if (Boolean.TRUE.equals(r.clearScope()) || Boolean.TRUE.equals(r.clearSiteScope())) m.setSiteIds(null);
        if (r.branchIds() != null) m.setBranchIds(r.branchIds());
        if (r.siteIds() != null) m.setSiteIds(r.siteIds());
        terminateSessions(m.getUserId());
        audit.record("USER_UPDATE", "Membership", m.getId(), Map.of("role", m.getRole().name(), "status", m.getStatus()));
        return view(m);
    }

    @PostMapping("/{membershipId}/terminate-sessions")
    @Transactional
    public Map<String, String> terminate(@PathVariable UUID membershipId) {
        Authz.require(Perm.USER_MANAGE);
        terminateSessions(find(membershipId).getUserId());
        audit.record("USER_SESSIONS_TERMINATED", "Membership", membershipId, Map.of());
        return Map.of("status", "SESSIONS_REVOKED");
    }

    /**
     * Forgotten password: sets a one-time temporary password (returned once) and signs the person out everywhere.
     * Only owners may reset an owner. Someone who also belongs to another agency can't be reset from here,
     * because the password is shared across agencies.
     */
    @PostMapping("/{membershipId}/reset-password")
    @Transactional
    public Map<String, Object> resetPassword(@PathVariable UUID membershipId) {
        Authz.require(Perm.USER_MANAGE);
        Membership m = find(membershipId);
        if (m.getUserId().equals(Authz.userId())) throw ApiException.invalidState("Use Change password for your own account");
        if (m.getRole() == Role.OWNER && Authz.user().role() != Role.OWNER) throw ApiException.forbidden("Only owners can reset an owner's password");
        User u = users.findById(m.getUserId()).orElseThrow();
        if (u.isPlatformAdmin() || memberships.findByUserIdAndStatus(u.getId(), "ACTIVE").stream().anyMatch(x -> !x.getTenantId().equals(m.getTenantId())))
            throw ApiException.invalidState("This person also uses another organisation; they must reset it themselves");
        String temp = SignupService.temporaryPassword();
        u.setPasswordHash(encoder.encode(temp));
        terminateSessions(u.getId());
        audit.record("USER_PASSWORD_RESET", "Membership", m.getId(), Map.of());
        return Map.of("login", u.getEmail() != null ? u.getEmail() : u.getPhone(), "temporaryPassword", temp);
    }

    /** Self-SMS sign-in binds a number to one phone; this lets the person sign in on a new phone once. */
    @PostMapping("/{membershipId}/allow-new-phone")
    @Transactional
    public Map<String, Object> allowNewPhone(@PathVariable UUID membershipId) {
        Authz.require(Perm.USER_MANAGE);
        Membership m = find(membershipId);
        User u = users.findById(m.getUserId()).orElseThrow();
        u.setAppDeviceId(null);
        u.setAppDeviceBoundAt(null);
        terminateSessions(u.getId());
        audit.record("USER_APP_PHONE_RESET", "Membership", m.getId(), Map.of());
        return Map.of("status", "NEW_PHONE_ALLOWED");
    }

    private void terminateSessions(UUID userId) {
        users.findById(userId).ifPresent(u -> u.setTokenEpoch(u.getTokenEpoch() + 1));
        revocation.evict(userId);
    }

    private Membership find(UUID id) {
        return memberships.findByIdAndTenantId(id, TenantContext.requireTenant()).orElseThrow(() -> ApiException.notFound("User"));
    }

    private MemberView view(Membership m) {
        User u = users.findById(m.getUserId()).orElseThrow();
        return new MemberView(m.getId(), u.getId(), u.getFullName(), u.getEmail(), u.getPhone(), m.getRole(), m.getStatus(),
                m.getBranchIds(), m.getSiteIds(), m.getClientId(), m.getEmployeeId(), u.getAppDeviceId() != null);
    }

    private static String randomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }
}
