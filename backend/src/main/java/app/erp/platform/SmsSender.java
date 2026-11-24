package app.erp.platform;

import app.erp.common.AppProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Sends one-time codes by SMS. See {@link AppProperties.Sms} for the providers. */
@Component
public class SmsSender {
    private static final Logger log = LoggerFactory.getLogger(SmsSender.class);
    private final AppProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public SmsSender(AppProperties props) { this.props = props; }

    /** Returns false if the message could not be handed to the provider. */
    public boolean send(String phone10, String otp, String message) {
        var s = props.sms();
        String provider = s == null || s.provider() == null ? "log" : s.provider();
        if (!"http".equals(provider)) {
            log.info("OTP for ...{}: {} (SMS_PROVIDER=log, not sent)", phone10.substring(Math.max(0, phone10.length() - 4)), otp);
            return true;
        }
        try {
            String url = s.httpUrl().replace("{phone}", enc(phone10)).replace("{otp}", enc(otp)).replace("{message}", enc(message));
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
            if (s.httpAuthHeader() != null && s.httpAuthHeader().contains(":")) {
                int i = s.httpAuthHeader().indexOf(':');
                b.header(s.httpAuthHeader().substring(0, i).trim(), s.httpAuthHeader().substring(i + 1).trim());
            }
            b = "POST".equalsIgnoreCase(s.httpMethod()) ? b.POST(HttpRequest.BodyPublishers.noBody()) : b.GET();
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 300) log.warn("SMS gateway returned {}: {}", r.statusCode(), r.body().length() > 200 ? r.body().substring(0, 200) : r.body());
            return r.statusCode() < 300;
        } catch (Exception e) {
            log.warn("SMS send failed: {}", e.getMessage());
            return false;
        }
    }

    private static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }
}
