package app.erp.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Subscription plans the platform operator assigns to agencies. A plan sets default limits; the operator
 * can override any limit per agency. {@code null} means unlimited. Users counts office staff only
 * (guard and client logins are never limited, so every guard can use the app).
 */
public final class PlanCatalog {
    private PlanCatalog() {}

    public record Plan(String code, String name, Integer maxEmployees, Integer maxSites, Integer maxUsers, String description) {}

    public static final List<Plan> PLANS = List.of(
            new Plan("STARTER", "Starter", 50, 5, 5, "Small agency: up to 50 staff, 5 sites"),
            new Plan("GROWTH", "Growth", 500, 50, 25, "Growing agency: up to 500 staff, 50 sites"),
            new Plan("ENTERPRISE", "Enterprise", null, null, null, "No limits"));

    public static Plan get(String code) {
        return PLANS.stream().filter(p -> p.code().equals(code)).findFirst().orElse(null);
    }

    public static Map<String, Object> view(Plan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", p.code());
        m.put("name", p.name());
        m.put("maxEmployees", p.maxEmployees());
        m.put("maxSites", p.maxSites());
        m.put("maxUsers", p.maxUsers());
        m.put("description", p.description());
        return m;
    }
}
