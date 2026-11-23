package app.erp.attendance;

import app.erp.common.ApiException;
import app.erp.hr.EmployeeRepository;
import app.erp.security.Authz;
import app.erp.security.Perm;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Photo evidence for attendance. Apps upload a compressed JPEG (base64) first and then reference
 * the returned id from the punch, roll-call or register entry.
 */
@RestController
@RequestMapping("/v1/attendance/photos")
public class PhotoController {
    static final int MAX_BYTES = 1_500_000;
    private static final Set<String> KINDS = Set.of("SELFIE", "TEAM", "REGISTER", "PROFILE");
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final AttendancePhotoRepository photos;
    private final EmployeeRepository employees;
    private final JdbcTemplate jdbc;

    public PhotoController(AttendancePhotoRepository photos, EmployeeRepository employees, JdbcTemplate jdbc) {
        this.photos = photos;
        this.employees = employees;
        this.jdbc = jdbc;
    }

    public record Upload(String kind, String contentType, String dataBase64, UUID siteId, UUID employeeId, Instant takenAt) {}

    @PostMapping
    @Transactional
    public Map<String, Object> upload(@RequestBody Upload u) {
        var user = Authz.requireAny(Perm.ATTENDANCE_SELF, Perm.ATTENDANCE_MARK, Perm.ATTENDANCE_KIOSK, Perm.ATTENDANCE_ADJUST, Perm.HR_WRITE);
        String kind = u.kind() == null ? "SELFIE" : u.kind();
        if (!KINDS.contains(kind)) throw ApiException.field("kind", "SELFIE, TEAM, REGISTER or PROFILE");
        if ("PROFILE".equals(kind)) Authz.requireAny(Perm.HR_WRITE, Perm.ATTENDANCE_MARK);
        String type = u.contentType() == null ? "image/jpeg" : u.contentType();
        if (!TYPES.contains(type)) throw ApiException.field("contentType", "JPEG, PNG or WebP");
        if (u.dataBase64() == null || u.dataBase64().isBlank()) throw ApiException.field("dataBase64", "Required");
        byte[] data;
        try {
            String b64 = u.dataBase64();
            int comma = b64.indexOf(',');
            if (b64.startsWith("data:") && comma > 0) b64 = b64.substring(comma + 1);
            data = Base64.getMimeDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw ApiException.field("dataBase64", "Not valid base64");
        }
        if (data.length == 0 || data.length > MAX_BYTES) throw ApiException.field("dataBase64", "Photo must be under 1.5 MB");
        if (u.siteId() != null) Authz.requireSite(u.siteId(), "Site");
        UUID employeeId = u.employeeId();
        if (user.isGuard()) employeeId = user.employeeId();
        else if (employeeId != null && !employees.existsById(employeeId)) throw ApiException.notFound("Employee");

        AttendancePhoto p = new AttendancePhoto();
        p.setKind(kind);
        p.setContentType(type);
        p.setData(data);
        p.setSizeBytes(data.length);
        p.setSha256(sha256(data));
        p.setSiteId(u.siteId());
        p.setEmployeeId(employeeId);
        p.setTakenAt(u.takenAt() == null ? Instant.now() : u.takenAt());
        p.setUploadedBy(user.userId());
        photos.saveAndFlush(p);
        if ("PROFILE".equals(kind) && employeeId != null)
            jdbc.update("update employees set profile_photo_id = ?, updated_at = now() where id = ?", p.getId(), employeeId);
        return Map.of("id", p.getId(), "sha256", p.getSha256(), "sizeBytes", p.getSizeBytes());
    }

    /** Reviewers see any photo in their scope; guards only their own; kiosks only profile photos of their sites' staff. */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> get(@PathVariable UUID id) {
        var user = Authz.user();
        AttendancePhoto p = photos.findById(id).orElseThrow(() -> ApiException.notFound("Photo"));
        boolean ok;
        if (Authz.has(Perm.ATTENDANCE_READ) || Authz.has(Perm.HR_READ)) ok = p.getSiteId() == null || user.siteAllowed(p.getSiteId());
        else if (Authz.has(Perm.ATTENDANCE_KIOSK)) ok = "PROFILE".equals(p.getKind());
        else ok = user.employeeId() != null && user.employeeId().equals(p.getEmployeeId());
        if (!ok) throw ApiException.notFound("Photo");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(p.getContentType()))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30)).cachePrivate()).body(p.getData());
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
