package app.erp.platform;

import app.erp.common.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first platform operator from PLATFORM_ADMIN_EMAIL / PLATFORM_ADMIN_PASSWORD, or flags an existing
 * user. An existing user's password is never changed here.
 */
@Component
public class PlatformAdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);
    private final AppProperties props;
    private final UserRepository users;
    private final PasswordEncoder encoder;

    public PlatformAdminBootstrap(AppProperties props, UserRepository users, PasswordEncoder encoder) {
        this.props = props;
        this.users = users;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var p = props.platform();
        if (p == null || p.adminEmail() == null || p.adminEmail().isBlank()) return;
        String email = p.adminEmail().trim().toLowerCase();
        User u = users.findByEmailIgnoreCase(email).orElse(null);
        if (u == null) {
            if (p.adminPassword() == null || p.adminPassword().length() < 10) {
                log.warn("PLATFORM_ADMIN_EMAIL set but PLATFORM_ADMIN_PASSWORD missing or shorter than 10 characters; not created");
                return;
            }
            u = new User();
            u.setEmail(email);
            u.setFullName("Platform admin");
            u.setPasswordHash(encoder.encode(p.adminPassword()));
            log.info("Created platform admin {}", email);
        } else if (!u.isPlatformAdmin()) {
            log.info("Granted platform admin to existing user {}", email);
        }
        u.setPlatformAdmin(true);
        users.save(u);
    }
}
