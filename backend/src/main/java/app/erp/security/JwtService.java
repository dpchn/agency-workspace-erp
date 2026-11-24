package app.erp.security;

import app.erp.common.AppProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

/**
 * Issues short-lived HS256 access tokens. The resource-server side is standard
 * Spring Security, so swapping to an external OIDC provider only means replacing
 * the decoder bean and the membership-resolution step in AuthController.
 */
@Configuration
public class JwtService {
    private final AppProperties props;
    private final SecretKey key;

    public JwtService(AppProperties props) {
        this.props = props;
        byte[] bytes = props.security().jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalStateException("app.security.jwt-secret must be at least 32 bytes");
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    public record TokenClaims(UUID userId, String name, int epoch, boolean platformAdmin,
                              UUID tenantId, UUID membershipId, Role role,
                              List<UUID> branchIds, List<UUID> siteIds, UUID clientId, UUID employeeId) {}

    public String issue(JwtEncoder encoder, TokenClaims c) { return issue(encoder, c, false); }

    /** mobile: app sign-ins last mobileTokenDays; leaving / deactivation still ends them at once (token epoch). */
    public String issue(JwtEncoder encoder, TokenClaims c, boolean mobile) {
        Instant now = Instant.now();
        long minutes = mobile ? props.security().mobileTokenDays() * 1440L : props.security().accessTokenMinutes();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder()
                .subject(c.userId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(minutes, ChronoUnit.MINUTES))
                .claim("name", c.name())
                .claim("ep", c.epoch())
                .claim("pa", c.platformAdmin());
        if (c.tenantId() != null) {
            b.claim("tid", c.tenantId().toString())
             .claim("mid", c.membershipId().toString())
             .claim("role", c.role().name());
            if (c.branchIds() != null) b.claim("br", c.branchIds().stream().map(UUID::toString).toList());
            if (c.siteIds() != null) b.claim("st", c.siteIds().stream().map(UUID::toString).toList());
            if (c.clientId() != null) b.claim("cid", c.clientId().toString());
            if (c.employeeId() != null) b.claim("eid", c.employeeId().toString());
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, b.build())).getTokenValue();
    }
}
