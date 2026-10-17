package com.techcrm.crm.sap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Starts processes in SAP Build Process Automation and reads their results.
 *
 * <pre>
 *   POST {token}                                    client credentials -> bearer token
 *   POST {api}/workflow-instances                   start a process   -> instance id
 *   GET  {api}/workflow-instances/{id}              status            -> RUNNING | COMPLETED | ...
 *   GET  {api}/workflow-instances/{id}/context      the automation's output
 * </pre>
 *
 * Starting a process does not return its result: the platform answers in
 * milliseconds with an instance id while the automation runs for seconds
 * afterwards. Reading the answer therefore means polling the instance and then
 * reading its context, which is where the automation writes what it concluded.
 * The context is the contract between the two systems — an automation whose
 * outputs are not mapped to process context variables produces an instance that
 * completes with nothing in it.
 *
 * Tokens are cached until shortly before they expire. They are valid for hours,
 * and fetching one per validation would double the latency of every lead.
 *
 * Nothing here throws on a platform failure. A validation that cannot run must
 * degrade to "not checked", never to "lead refused" — see LeadValidationService.
 */
@Component
public class SapProcessTriggerClient {

    private static final Logger log = LoggerFactory.getLogger(SapProcessTriggerClient.class);

    /** Refresh this long before the token actually expires, so a request never
     *  races the expiry it was checked against. */
    private static final Duration TOKEN_SAFETY_MARGIN = Duration.ofMinutes(5);

    private final SapProcessProperties properties;
    private final RestClient http;

    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public SapProcessTriggerClient(SapProcessProperties properties) {
        this.properties = properties;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getRequestTimeout());
        factory.setReadTimeout(properties.getRequestTimeout());
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /** What the platform reports about a running or finished instance. */
    public record InstanceStatus(String status, boolean finished, boolean succeeded) {

        static InstanceStatus of(String raw) {
            String s = raw == null ? "" : raw.toUpperCase();
            boolean finished = s.equals("COMPLETED") || s.equals("CANCELED")
                    || s.equals("ERRONEOUS") || s.equals("SUSPENDED");
            return new InstanceStatus(s, finished, s.equals("COMPLETED"));
        }
    }

    /**
     * Starts a process.
     *
     * @return the instance id, or null when the platform could not be reached
     *         or is not configured. A null is a degraded run, not an error.
     */
    public String startProcess(String definitionId, Map<String, Object> context) {
        if (!isConfigured()) {
            // Said out loud. A silent null here is indistinguishable from a
            // platform that refused the call, and the caller logs only that it
            // could not start — which sends anyone debugging it to the
            // platform rather than to their own configuration.
            log.warn("Not starting SAP process {}: the platform is not configured", definitionId);
            return null;
        }
        if (definitionId == null || definitionId.isBlank()) {
            log.warn("Not starting an SAP process: no definition id was given");
            return null;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("definitionId", definitionId);
            body.put("context", context == null ? Map.of() : context);

            Map<?, ?> response = http.post()
                    .uri(api("/workflow-instances"))
                    .headers(credentials())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            String id = response == null ? null : asString(response.get("id"));
            if (id == null) {
                log.warn("SAP accepted the process start but returned no instance id: {}", response);
            }
            return id;

        } catch (RestClientException e) {
            log.error("Could not start SAP process {}", definitionId, e);
            return null;
        }
    }

    /** @return the instance's status, or null when it could not be read. */
    public InstanceStatus instanceStatus(String instanceId) {
        if (!isConfigured() || instanceId == null) {
            return null;
        }
        try {
            Map<?, ?> response = http.get()
                    .uri(api("/workflow-instances/" + instanceId))
                    .headers(credentials())
                    .retrieve()
                    .body(Map.class);

            return response == null ? null : InstanceStatus.of(asString(response.get("status")));

        } catch (RestClientException e) {
            log.warn("Could not read status of SAP instance {}", instanceId, e);
            return null;
        }
    }

    /**
     * Reads what the automation wrote back.
     *
     * An empty map from a completed instance almost always means the
     * automation's outputs were never mapped to process context variables,
     * rather than that the automation produced nothing.
     */
    public Map<String, Object> instanceContext(String instanceId) {
        if (!isConfigured() || instanceId == null) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response =
                    http.get()
                    .uri(api("/workflow-instances/" + instanceId + "/context"))
                    .headers(credentials())
                    .retrieve()
                    .body(Map.class);

            return response == null ? Map.of() : Collections.unmodifiableMap(response);

        } catch (RestClientException e) {
            log.warn("Could not read context of SAP instance {}", instanceId, e);
            return Map.of();
        }
    }

    /* ------------------------------------------------------------- internals */

    /**
     * The credentials every workflow API call carries.
     *
     * Two of them, where the tenant asks for two. The bearer token says which
     * tenant is calling; the API key says which trigger is being invoked, and
     * a tenant configured to require it refuses the call outright without it.
     * Applied here rather than on the client so the token endpoint, which
     * wants neither, does not receive them.
     */
    private Consumer<HttpHeaders> credentials() {
        String bearer = "Bearer " + token();
        String apiKey = properties.getApiKey();
        return headers -> {
            headers.set("Authorization", bearer);
            if (apiKey != null && !apiKey.isBlank()) {
                headers.set("irpa-api-key", apiKey.trim());
            }
        };
    }

    /** Cached until shortly before expiry; fetched again after that. */
    private String token() {
        String current = cachedToken;
        if (current != null && Instant.now().isBefore(tokenExpiresAt)) {
            return current;
        }
        synchronized (this) {
            if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)) {
                return cachedToken;
            }

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", properties.getClientId());
            form.add("client_secret", properties.getClientSecret());

            Map<?, ?> response = http.post()
                    .uri(properties.getTokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);

            if (response == null || response.get("access_token") == null) {
                throw new RestClientException("SAP token endpoint returned no access_token");
            }

            cachedToken = asString(response.get("access_token"));
            long expiresIn = response.get("expires_in") instanceof Number n ? n.longValue() : 3600L;
            tokenExpiresAt = Instant.now().plusSeconds(expiresIn).minus(TOKEN_SAFETY_MARGIN);
            return cachedToken;
        }
    }

    /**
     * A workflow API URL, carrying the environment where one is configured.
     *
     * Appended here rather than folded into the configured base URL so that
     * the base stays exactly what the service key reports, and so a landscape
     * that does not want the parameter simply leaves it blank.
     */
    private String api(String path) {
        String base = properties.getApiBaseUrl().replaceAll("/+$", "");
        String url = base + path;

        String environmentId = properties.getEnvironmentId();
        if (environmentId == null || environmentId.isBlank()) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?")
                + "environmentId=" + URLEncoder.encode(environmentId.trim(), StandardCharsets.UTF_8);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
