package app.erp.platform;

import app.erp.common.ApiException;
import app.erp.common.CurrentUser;
import app.erp.common.TenantContext;
import app.erp.security.Authz;
import app.erp.security.JwtService;
import app.erp.security.Perm;
import app.erp.security.TokenRevocationValidator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
public class AuthController {
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final TenantRepository tenants;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final JwtEncoder jwtEncoder;
    private final TokenRevocationValidator revocation;
    @org.springframework.beans.factory.annotation.Autowired private app.erp.common.AppProperties props;
    @org.springframework.beans.factory.annotation.Autowired private OtpService otp;

    public AuthController(UserRepository users, MembershipRepository memberships, TenantRepository tenants,
                          PasswordEncoder encoder, JwtService jwt, JwtEncoder jwtEncoder, TokenRevocationValidator revocation) {
        this.users = users;
        this.memberships = memberships;
        this.tenants = tenants;
        this.encoder = encoder;
        this.jwt = jwt;
        this.jwtEncoder = jwtEncoder;
        this.revocation = revocation;
    }

    public record LoginRequest(@NotBlank String login, @NotBlank String password, UUID tenantId, Boolean mobile) {}

    public record MembershipView(UUID membershipId, UUID tenantId, String tenantName, String tenantSlug, String role) {}

    public record LoginResponse(String accessToken, UUID userId, String name, boolean platformAdmin,
                                UUID tenantId, String role, List<MembershipView> memberships) {}

    @PostMapping("/auth/login")
    @Transactional
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        String login = req.login().trim();
        Optional<User> found = login.contains("@") ? users.findByEmailIgnoreCase(login) : users.findByPhone(login);
        User u = found.filter(x -> "ACTIVE".equals(x.getStatus()))
                .filter(x -> encoder.matches(req.password(), x.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid login or password"));
        u.setLastLoginAt(Instant.now());
        return issueFor(u, req.tenantId(), Boolean.TRUE.equals(req.mobile()));
    }

    public record SwitchRequest(UUID tenantId) {}

    @PostMapping("/auth/switch-tenant")
    @Transactional(readOnly = true)
    public LoginResponse switchTenant(@RequestBody SwitchRequest req) {
        User u = users.findById(Authz.user().userId()).orElseThrow(() -> ApiException.notFound("User"));
        return issueFor(u, req.tenantId());
    }

    /** Terminates every session of the caller (all devices). */
    @PostMapping("/auth/logout-all")
    @Transactional
    public Map<String, String> logoutAll() {
        User u = users.findById(Authz.user().userId()).orElseThrow(() -> ApiException.notFound("User"));
        u.setTokenEpoch(u.getTokenEpoch() + 1);
        revocation.evict(u.getId());
        return Map.of("status", "SESSIONS_REVOKED");
    }

    public record ChangePassword(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    @PostMapping("/auth/change-password")
    @Transactional
    public Map<String, String> changePassword(@Valid @RequestBody ChangePassword req) {
        User u = users.findById(Authz.user().userId()).orElseThrow(() -> ApiException.notFound("User"));
        if (!encoder.matches(req.currentPassword(), u.getPasswordHash())) throw ApiException.field("currentPassword", "Incorrect password");
        if (req.newPassword().length() < 8) throw ApiException.field("newPassword", "Minimum 8 characters");
        u.setPasswordHash(encoder.encode(req.newPassword()));
        u.setTokenEpoch(u.getTokenEpoch() + 1);
        revocation.evict(u.getId());
        return Map.of("status", "PASSWORD_CHANGED");
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    public Map<String, Object> me() {
        CurrentUser cu = Authz.user();
        User u = users.findById(cu.userId()).orElseThrow(() -> ApiException.notFound("User"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", u.getId());
        out.put("name", u.getFullName());
        out.put("email", u.getEmail());
        out.put("phone", u.getPhone());
        out.put("platformAdmin", u.isPlatformAdmin());
        out.put("memberships", membershipViews(u.getId()));
        if (cu.tenantId() != null) {
            Tenant t = tenants.findById(cu.tenantId()).orElseThrow(() -> ApiException.notFound("Tenant"));
            out.put("tenant", Map.of("id", t.getId(), "name", t.getName(), "slug", t.getSlug(), "status", t.getStatus(),
                    "timezone", t.getTimezone(), "currency", t.getCurrency(), "branding", t.getBranding()));
            out.put("role", cu.role());
            out.put("permissions", cu.role().perms().stream().map(Perm::name).sorted().toList());
            out.put("employeeId", cu.employeeId());
            out.put("clientId", cu.clientId());
            out.put("siteIds", cu.siteIds());
            out.put("branchIds", cu.branchIds());
        }
        return out;
    }

    private List<MembershipView> membershipViews(UUID userId) {
        return memberships.findByUserIdAndStatus(userId, "ACTIVE").stream().map(m -> {
            Tenant t = tenants.findById(m.getTenantId()).orElse(null);
            return new MembershipView(m.getId(), m.getTenantId(), t == null ? "?" : t.getName(), t == null ? "?" : t.getSlug(), m.getRole().name());
        }).toList();
    }

    // ---------------- app sign-in with mobile number + SMS code (no self-registration)

    public record OtpRequest(String phone, String purpose) {}

    @PostMapping("/auth/otp/request")
    public Map<String, Object> otpRequest(@RequestBody OtpRequest r) {
        return otp.request(r.phone(), r.purpose() == null ? "LOGIN" : r.purpose());
    }

    public record OtpVerify(String phone, String code, UUID tenantId, Boolean mobile) {}

    @PostMapping("/auth/otp/verify")
    public LoginResponse otpVerify(@RequestBody OtpVerify r) {
        User u = otp.verify(r.phone(), r.code(), "LOGIN");
        u.setLastLoginAt(Instant.now());
        users.save(u);
        return issueFor(u, r.tenantId(), !Boolean.FALSE.equals(r.mobile()));
    }

    public record OtpReset(String phone, String code, String newPassword, Boolean mobile) {}

    /** Forgot password: SMS code, then a new password. Signs out every other device. */
    @PostMapping("/auth/otp/reset-password")
    public LoginResponse otpReset(@RequestBody OtpReset r) {
        if (r.newPassword() == null || r.newPassword().length() < 8) throw ApiException.field("newPassword", "Minimum 8 characters");
        User u = otp.verify(r.phone(), r.code(), "RESET");
        u.setPasswordHash(encoder.encode(r.newPassword()));
        u.setTokenEpoch(u.getTokenEpoch() + 1);
        u.setLastLoginAt(Instant.now());
        users.save(u);
        revocation.evict(u.getId());
        return issueFor(u, null, !Boolean.FALSE.equals(r.mobile()));
    }

    private LoginResponse issueFor(User u, UUID requestedTenant) { return issueFor(u, requestedTenant, false); }

    private LoginResponse issueFor(User u, UUID requestedTenant, boolean mobile) {
        List<Membership> ms = memberships.findByUserIdAndStatus(u.getId(), "ACTIVE");
        Membership m = null;
        if (requestedTenant != null) {
            m = ms.stream().filter(x -> x.getTenantId().equals(requestedTenant)).findFirst()
                    .orElseThrow(() -> ApiException.forbidden("No access to that tenant"));
        } else if (!ms.isEmpty()) {
            m = ms.get(0);
        }
        if (m == null && !u.isPlatformAdmin()) throw ApiException.forbidden("User has no active membership");
        if (m != null) {
            Tenant t = tenants.findById(m.getTenantId()).orElseThrow();
            if ("SUSPENDED".equals(t.getStatus()) || "CLOSED".equals(t.getStatus()))
                throw new ApiException(HttpStatus.FORBIDDEN, "TENANT_SUSPENDED", "This organisation's account is suspended");
            if (t.trialExpired())
                throw new ApiException(HttpStatus.FORBIDDEN, "TRIAL_EXPIRED", "The free trial has ended. Contact "
                        + props.brand().supportEmail() + " to activate your account");
        }
        var claims = new JwtService.TokenClaims(u.getId(), u.getFullName(), u.getTokenEpoch(), u.isPlatformAdmin(),
                m == null ? null : m.getTenantId(), m == null ? null : m.getId(), m == null ? null : m.getRole(),
                m == null || m.getBranchIds() == null ? null : Arrays.asList(m.getBranchIds()),
                m == null || m.getSiteIds() == null ? null : Arrays.asList(m.getSiteIds()),
                m == null ? null : m.getClientId(), m == null ? null : m.getEmployeeId());
        return new LoginResponse(jwt.issue(jwtEncoder, claims, mobile), u.getId(), u.getFullName(), u.isPlatformAdmin(),
                m == null ? null : m.getTenantId(), m == null ? null : m.getRole().name(), membershipViews(u.getId()));
    }

}
