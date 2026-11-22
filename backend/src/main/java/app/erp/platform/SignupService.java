package app.erp.platform;

import app.erp.common.ApiException;
import app.erp.common.AppProperties;
import app.erp.security.Role;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service agency onboarding (like a society joining MyGate): creates the tenant,
 * its owner, and sensible editable defaults (branch, service types, shifts, pay rules).
 */
@Service
public class SignupService {
    private final TenantRepository tenants;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;
    private final AppProperties props;

    public SignupService(TenantRepository tenants, UserRepository users, MembershipRepository memberships,
                         PasswordEncoder encoder, JdbcTemplate jdbc, AppProperties props) {
        this.tenants = tenants;
        this.users = users;
        this.memberships = memberships;
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.props = props;
    }

    public record SignupRequest(String agencyName, String slug, String stateCode, String gstin,
                                String ownerName, String ownerEmail, String ownerPhone, String password) {}

    @Transactional
    public Tenant signup(SignupRequest r) {
        if (r.agencyName() == null || r.agencyName().isBlank()) throw ApiException.field("agencyName", "Required");
        if (r.ownerName() == null || r.ownerName().isBlank()) throw ApiException.field("ownerName", "Required");
        if (r.ownerEmail() == null || !r.ownerEmail().contains("@")) throw ApiException.field("ownerEmail", "Valid e-mail required");
        if (r.password() == null || r.password().length() < 8) throw ApiException.field("password", "Minimum 8 characters");
        String slug = (r.slug() == null || r.slug().isBlank() ? r.agencyName() : r.slug())
                .toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.length() < 3) throw ApiException.field("slug", "At least 3 letters/digits");
        if (tenants.existsBySlug(slug)) throw ApiException.field("slug", "Already taken");

        Tenant t = new Tenant();
        t.setSlug(slug);
        t.setName(r.agencyName().trim());
        t.setLegalName(r.agencyName().trim());
        t.setStatus("TRIAL");
        t.setPlan("STARTER");
        t.setStateCode(r.stateCode());
        t.setGstin(r.gstin());
        t.setTimezone("Asia/Kolkata");
        t.setCurrency("INR");
        t.setFiscalYearStartMonth(4);
        t.setSettings(new HashMap<>());
        t.setBranding(new HashMap<>());
        t.setTrialEndsAt(Instant.now().plus(props.signup().trialDays(), ChronoUnit.DAYS));
        tenants.saveAndFlush(t);

        User u = users.findByEmailIgnoreCase(r.ownerEmail().trim()).orElse(null);
        if (u == null) {
            u = new User();
            u.setEmail(r.ownerEmail().trim().toLowerCase());
            u.setPhone(r.ownerPhone());
            u.setFullName(r.ownerName().trim());
            u.setPasswordHash(encoder.encode(r.password()));
            users.saveAndFlush(u);
        }
        Membership m = new Membership();
        m.setTenantId(t.getId());
        m.setUserId(u.getId());
        m.setRole(Role.OWNER);
        memberships.saveAndFlush(m);

        // Bind RLS for the rest of this transaction only, then seed defaults.
        jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, t.getId().toString());
        seedDefaults(t.getId(), r.stateCode());
        return t;
    }

    private void seedDefaults(UUID tenant, String stateCode) {
        UUID branch = UUID.randomUUID();
        jdbc.update("insert into branches(id, tenant_id, code, name, state_code) values (?,?,?,?,?)",
                branch, tenant, "HQ", "Head Office", stateCode);

        String[][] serviceTypes = {
                {"GUARD", "Security Guard", "SECURITY", "8", "POLICE_VERIFICATION"},
                {"LADY_GUARD", "Lady Security Guard", "SECURITY", "8", "POLICE_VERIFICATION"},
                {"HEAD_GUARD", "Head Guard", "SECURITY", "8", "POLICE_VERIFICATION"},
                {"GUNMAN", "Armed Guard / Gunman", "SECURITY", "8", "POLICE_VERIFICATION,ARMS_LICENSE"},
                {"SUPERVISOR", "Field Supervisor", "SECURITY", "8", ""},
                {"HOUSEKEEPING", "Housekeeping Staff", "FACILITY", "8", ""}};
        for (String[] s : serviceTypes) {
            UUID id = UUID.randomUUID();
            jdbc.update("insert into service_types(id, tenant_id, code, name, category, default_shift_hours, required_documents) values (?,?,?,?,?,?,?)",
                    ps -> {
                        ps.setObject(1, id); ps.setObject(2, tenant); ps.setString(3, s[0]); ps.setString(4, s[1]); ps.setString(5, s[2]);
                        ps.setBigDecimal(6, new java.math.BigDecimal(s[3]));
                        ps.setArray(7, ps.getConnection().createArrayOf("varchar", s[4].isEmpty() ? new String[0] : s[4].split(",")));
                    });
        }

        Object[][] shifts = {
                {"A", "Morning 8h", "06:00", "14:00"}, {"B", "Evening 8h", "14:00", "22:00"}, {"C", "Night 8h", "22:00", "06:00"},
                {"D12", "Day 12h", "08:00", "20:00"}, {"N12", "Night 12h", "20:00", "08:00"}, {"GEN", "General 9-6", "09:00", "18:00"}};
        for (Object[] s : shifts) {
            jdbc.update("insert into shift_templates(id, tenant_id, code, name, start_time, end_time) values (?,?,?,?,?::time,?::time)",
                    UUID.randomUUID(), tenant, s[0], s[1], s[2], s[3]);
        }

        // Illustrative statutory components. MUST be validated by a payroll professional per state.
        String comp = """
                insert into salary_components(id, tenant_id, code, name, kind, calc_type, base, percent, base_ceiling,
                    applies_if_gross_upto, slabs, sequence) values (?,?,?,?,?,?,?,?,?,?,?::jsonb,?)""";
        jdbc.update(comp, UUID.randomUUID(), tenant, "PF_EE", "Provident Fund (Employee)", "DEDUCTION", "PERCENT", "DUTY_WAGE", 12, 15000, null, null, 10);
        jdbc.update(comp, UUID.randomUUID(), tenant, "ESI_EE", "ESI (Employee)", "DEDUCTION", "PERCENT", "GROSS", 0.75, null, 21000, null, 20);
        jdbc.update(comp, UUID.randomUUID(), tenant, "PT", "Professional Tax", "DEDUCTION", "SLAB", "GROSS", null, null, null,
                "[{\"upto\":7500,\"amount\":0},{\"upto\":10000,\"amount\":175},{\"upto\":null,\"amount\":200}]", 30);
        jdbc.update(comp, UUID.randomUUID(), tenant, "PF_ER", "Provident Fund (Employer)", "EMPLOYER_CONTRIBUTION", "PERCENT", "DUTY_WAGE", 13, 15000, null, null, 40);
        jdbc.update(comp, UUID.randomUUID(), tenant, "ESI_ER", "ESI (Employer)", "EMPLOYER_CONTRIBUTION", "PERCENT", "GROSS", 3.25, null, 21000, null, 50);
    }
}
