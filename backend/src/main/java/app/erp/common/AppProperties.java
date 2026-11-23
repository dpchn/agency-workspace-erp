package app.erp.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Brand brand, Security security, Signup signup, Jobs jobs, Platform platform) {
    public record Brand(String name, String tagline, String supportEmail, String primaryColor, String logoUrl) {}
    public record Security(String jwtSecret, int accessTokenMinutes, String corsOrigins) {}
    public record Signup(boolean enabled, int trialDays) {}
    public record Jobs(boolean enabled) {}
    /** First platform operator, created/flagged at startup when set (so no SQL is needed after install). */
    public record Platform(String adminEmail, String adminPassword) {}
}
