package app.erp.platform;

import app.erp.common.ApiException;
import app.erp.common.AppProperties;
import app.erp.common.TenantContext;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Mobile number + SMS code sign-in and password reset for app users. There is no self-registration: a code is only
 * sent to numbers the agency has already added (an active user with an active membership in an active agency, and
 * for staff linked to an employee record, an active employee). Answers never reveal whether a number is known.
 * Not transactional on purpose: failed attempts are counted even when the request then fails.
 */
@Service
public class OtpService {
    static final int CODE_MINUTES = 5, MAX_ATTEMPTS = 5, MAX_PER_15_MIN = 3, RESEND_SECONDS = 30;
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final TenantRepository tenants;
    private final PasswordEncoder encoder;
    private final SmsSender sms;
    private final AppProperties props;
    private final SecureRandom random = new SecureRandom();

    public OtpService(JdbcTemplate jdbc, UserRepository users, MembershipRepository memberships, TenantRepository tenants,
                      PasswordEncoder encoder, SmsSender sms, AppProperties props) {
        this.jdbc = jdbc;
        this.users = users;
        this.memberships = memberships;
        this.tenants = tenants;
        this.encoder = encoder;
        this.sms = sms;
        this.props = props;
    }

    /** Last 10 digits of an Indian mobile number ("+91 98451 00002" -> "9845100002"), or null. */
    static String normalize(String phone) {
        if (phone == null) return null;
        String d = phone.replaceAll("\\D", "");
        if (d.length() > 10 && (d.startsWith("91") || d.startsWith("0"))) d = d.substring(d.length() - 10);
        return d.length() == 10 ? d : null;
    }

    public Map<String, Object> request(String phone, String purpose) {
        String p = normalize(phone);
        if (p == null) throw ApiException.field("phone", "Enter your 10-digit mobile number");
        if (!Set.of("LOGIN", "RESET").contains(purpose)) throw ApiException.field("purpose", "LOGIN or RESET");
        Integer recent = jdbc.queryForObject("select count(*) from otp_codes where phone = ? and created_at > now() - interval '15 minutes'", Integer.class, p);
        if (recent != null && recent >= MAX_PER_15_MIN)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_LIMIT", "Too many codes requested. Try again after 15 minutes");
        Integer last = jdbc.queryForObject("select count(*) from otp_codes where phone = ? and created_at > now() - make_interval(secs => ?)",
                Integer.class, p, RESEND_SECONDS);
        if (last != null && last > 0)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_WAIT", "Please wait " + RESEND_SECONDS + " seconds before asking again");
        Optional<User> u = eligibleUser(p);
        String code = String.format("%06d", random.nextInt(1_000_000));
        jdbc.update("insert into otp_codes(id, phone, purpose, code_hash, expires_at, sent) values (?,?,?,?,?,?)",
                UUID.randomUUID(), p, purpose, encoder.encode(code), java.sql.Timestamp.from(Instant.now().plusSeconds(CODE_MINUTES * 60L)), u.isPresent());
        boolean self = selfMode();
        if (u.isPresent() && !self) {
            String brand = props.brand() == null ? "DutyGrid" : props.brand().name();
            sms.send(p, code, code + " is your " + brand + " " + ("RESET".equals(purpose) ? "password reset" : "login")
                    + " code. Valid " + CODE_MINUTES + " minutes. Do not share it with anyone.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", true);   // same answer for unknown numbers
        out.put("expiresInSeconds", CODE_MINUTES * 60);
        if (props.sms() != null && props.sms().devEcho() && u.isPresent()) out.put("devCode", code);
        // self mode: the app sends this text from the phone's own SIM to the number and reads it back. Unknown
        // numbers get a code too, so the answer doesn't reveal who is registered.
        if (self) out.put("selfSms", Map.of("code", code, "message", "DutyGrid code: " + code));
        return out;
    }

    /** Checks the latest code for this number; returns the user it signs in. */
    public User verify(String phone, String code, String purpose) {
        String p = normalize(phone);
        if (p == null) throw ApiException.field("phone", "Enter your 10-digit mobile number");
        if (code == null || !code.trim().matches("\\d{6}")) throw ApiException.field("code", "Enter the 6-digit code");
        var rows = jdbc.queryForList("""
                select id, code_hash, attempts, expires_at > now() as live, sent from otp_codes
                where phone = ? and purpose = ? and consumed_at is null order by created_at desc limit 1""", p, purpose);
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("live"))) throw ApiException.field("code", "Code expired. Ask for a new one");
        var row = rows.get(0);
        if (((Number) row.get("attempts")).intValue() >= MAX_ATTEMPTS) throw ApiException.field("code", "Too many wrong tries. Ask for a new code");
        if (!encoder.matches(code.trim(), (String) row.get("code_hash")) || (!Boolean.TRUE.equals(row.get("sent")) && !selfMode())) {
            jdbc.update("update otp_codes set attempts = attempts + 1 where id = ?", row.get("id"));
            throw ApiException.field("code", "Wrong code");
        }
        jdbc.update("update otp_codes set consumed_at = now() where id = ?", row.get("id"));
        return eligibleUser(p).orElseThrow(() -> ApiException.forbidden("This number has no active account. Contact your agency"));
    }

    boolean selfMode() { return props.sms() != null && "self".equals(props.sms().provider()); }

    /**
     * Self-SMS sign-in can't be proven on the server (the code is on the phone), so the number is bound to the first
     * phone it signs in on; another phone is refused until the agency allows it (Users & roles → Allow new phone).
     */
    public void checkDevice(User u, String deviceId) {
        if (!selfMode()) return;
        if (deviceId == null || deviceId.isBlank()) throw ApiException.field("deviceId", "Update the app");
        if (u.getAppDeviceId() == null) {
            jdbc.update("update users set app_device_id = ?, app_device_bound_at = now() where id = ?", deviceId, u.getId());
            u.setAppDeviceId(deviceId);
        } else if (!u.getAppDeviceId().equals(deviceId)) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "NEW_PHONE",
                    "This number is already used on another phone. Ask your office to allow this phone");
        }
    }

    /** The active user behind this number, if they may use the app now. */
    Optional<User> eligibleUser(String phone10) {
        List<UUID> ids = jdbc.queryForList("select id from users where right(regexp_replace(coalesce(phone, ''), '\\D', '', 'g'), 10) = ?",
                UUID.class, phone10);
        if (ids.size() != 1) return Optional.empty();   // unknown, or ambiguous: never guess
        User u = users.findById(ids.get(0)).orElse(null);
        if (u == null || !"ACTIVE".equals(u.getStatus())) return Optional.empty();
        for (Membership m : memberships.findByUserIdAndStatus(u.getId(), "ACTIVE")) {
            Tenant t = tenants.findById(m.getTenantId()).orElse(null);
            if (t == null || Set.of("SUSPENDED", "CLOSED").contains(t.getStatus()) || t.trialExpired()) continue;
            if (m.getEmployeeId() == null) return Optional.of(u);
            String st = TenantContext.runAs(t.getId(), () -> jdbc.query("select status from employees where id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, m.getEmployeeId()));
            if ("ACTIVE".equals(st)) return Optional.of(u);
        }
        return Optional.empty();
    }
}
