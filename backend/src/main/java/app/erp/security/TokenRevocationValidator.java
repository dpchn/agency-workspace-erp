package app.erp.security;

import app.erp.platform.UserRepository;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/**
 * Session termination: tokens carry the user's token_epoch; bumping it (logout-all,
 * revoke, disable) invalidates every outstanding token. Cached briefly.
 */
@Component
public class TokenRevocationValidator {
    private final UserRepository users;
    private final ConcurrentHashMap<UUID, long[]> cache = new ConcurrentHashMap<>();
    private static final long TTL_MS = Duration.ofSeconds(30).toMillis();

    public TokenRevocationValidator(UserRepository users) { this.users = users; }

    public void check(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        long now = System.currentTimeMillis();
        long[] entry = cache.get(userId);
        if (entry == null || now - entry[1] > TTL_MS) {
            var u = users.findById(userId).orElse(null);
            long epoch = (u == null || !"ACTIVE".equals(u.getStatus())) ? -1 : u.getTokenEpoch();
            entry = new long[]{epoch, now};
            cache.put(userId, entry);
        }
        Object ep = jwt.getClaim("ep");
        long tokenEpoch = ep instanceof Number n ? n.longValue() : -2;
        if (entry[0] < 0 || entry[0] != tokenEpoch) throw new InvalidBearerTokenException("Session revoked");
    }

    public void evict(UUID userId) { cache.remove(userId); }
}
