package app.erp.security;

import app.erp.common.AppProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, TokenRevocationValidator revocation) throws Exception {
        http.csrf(c -> c.disable())
            .cors(c -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.POST, "/v1/auth/login", "/v1/auth/otp/**", "/v1/tenants").permitAll()
                .requestMatchers(HttpMethod.GET, "/v1/public/**").permitAll()
                .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(authConverter(revocation))))
            .addFilterAfter(new RequestContextFilter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** Role -> PERM_* authorities, so @PreAuthorize("hasAuthority('PERM_X')") works too. */
    private Converter<Jwt, AbstractAuthenticationToken> authConverter(TokenRevocationValidator revocation) {
        return jwt -> {
            revocation.check(jwt);
            Collection<GrantedAuthority> auths = new ArrayList<>();
            String role = jwt.getClaimAsString("role");
            if (role != null) {
                auths.add(new SimpleGrantedAuthority("ROLE_" + role));
                Role.valueOf(role).perms().forEach(p -> auths.add(new SimpleGrantedAuthority("PERM_" + p.name())));
            }
            if (Boolean.TRUE.equals(jwt.getClaimAsBoolean("pa"))) auths.add(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
            return new JwtAuthenticationToken(jwt, auths);
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration c = new CorsConfiguration();
        // Auth is a bearer token (no cookies), so allowing any origin cannot leak credentials.
        // Default "*" lets the app be opened via any host/IP; restrict with CORS_ORIGINS if desired.
        c.setAllowedOriginPatterns(Arrays.stream(props.security().corsOrigins().split(",")).map(String::trim).toList());
        c.setAllowCredentials(false);
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setExposedHeaders(List.of("X-Correlation-Id", "Content-Disposition"));
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return src;
    }
}
