package app.erp.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Brand brand, Security security, Signup signup, Jobs jobs, Platform platform, Sms sms) {
    public record Brand(String name, String tagline, String supportEmail, String primaryColor, String logoUrl) {}
    /** mobileTokenDays: how long an app sign-in lasts (guards should not need a new OTP every shift). */
    public record Security(String jwtSecret, int accessTokenMinutes, String corsOrigins, int mobileTokenDays) {}
    /**
     * OTP delivery. provider: log (writes to the server log; development) | http (GET/POST to httpUrl with {phone},
     * {otp}, {message} filled in, works with most Indian SMS gateways) | self (no gateway yet: the app sends the
     * code by SMS from the phone's own SIM to the typed number and reads it back; weaker, so the number is bound
     * to the first phone). devEcho returns the code in the API response: local testing only, never in production.
     */
    public record Sms(String provider, String httpUrl, String httpMethod, String httpAuthHeader, boolean devEcho) {}
    public record Signup(boolean enabled, int trialDays) {}
    public record Jobs(boolean enabled) {}
    /** First platform operator, created/flagged at startup when set (so no SQL is needed after install). */
    public record Platform(String adminEmail, String adminPassword) {}
}
