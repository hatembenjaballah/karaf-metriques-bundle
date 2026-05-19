package com.hbj.karaf_metriques_bundle;

import com.google.gson.Gson;
import org.osgi.service.component.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component(
    service = MailService.class,
    configurationPid = "com.hbj.karaf.metrics.alert",
    immediate = true
)
public class MailjetApiService implements MailService {

    private static final Logger LOG = LoggerFactory.getLogger(MailjetApiService.class);
    private static final String API_URL = "https://api.mailjet.com/v3.1/send";
    private final Gson gson = new Gson();

    private boolean enabled = false;
    private String apiKey;
    private String apiSecret;
    private String fromEmail;
    private String fromName;
    private String toEmail;
    private String toName;
    private String subject;

    @Activate
    public void activate(Map<String, Object> props) {
        configure(props);
    }

    @Modified
    public void modified(Map<String, Object> props) {
        configure(props);
    }

    private void configure(Map<String, Object> props) {
        if (props == null) return;
        enabled = Boolean.parseBoolean(props.getOrDefault("mail.enabled", "false").toString());
        if (!enabled) {
            LOG.info("Mail alerting (Mailjet API) is disabled");
            return;
        }
        apiKey = (String) props.get("mail.username");
        apiSecret = (String) props.get("mail.password");
        fromEmail = (String) props.get("mail.from");
        fromName = (String) props.getOrDefault("mail.from.name", "Karaf Metrics");
        toEmail = (String) props.get("mail.to");
        toName = (String) props.getOrDefault("mail.to.name", "Admin");
        subject = (String) props.getOrDefault("mail.subject", "Karaf Metrics Alert");
        LOG.info("Mailjet API configured for {} -> {}", fromEmail, toEmail);
    }

    @Override
    public void sendHtmlMail(String subject, String htmlBody) {
        if (!enabled || apiKey == null || apiSecret == null || fromEmail == null || toEmail == null) {
            LOG.warn("Mailjet not properly configured, skipping email");
            return;
        }

        try {
            URL url = new URL(API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            String auth = apiKey + ":" + apiSecret;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes("UTF-8"));
            conn.setRequestProperty("Authorization", "Basic " + encodedAuth);

            String jsonPayload = buildPayload(subject, htmlBody);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonPayload.getBytes("UTF-8"));
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 200 || responseCode == 201) {
                LOG.info("Alert email sent via Mailjet to {}", toEmail);
            } else {
                LOG.error("Mailjet API returned status {}", responseCode);
            }
            conn.disconnect();
        } catch (Exception e) {
            LOG.error("Failed to send alert via Mailjet API", e);
        }
    }

    private String buildPayload(String subject, String htmlBody) {
        Map<String, Object> message = Map.of(
            "From", Map.of("Email", fromEmail, "Name", fromName),
            "To", List.of(Map.of("Email", toEmail, "Name", toName)),
            "Subject", subject,
            "HTMLPart", htmlBody
        );
        Map<String, Object> payload = Map.of("Messages", List.of(message));
        return gson.toJson(payload);
    }
}