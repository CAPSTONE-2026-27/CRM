package com.techcrm.crm.dealflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.techcrm.crm.ai.AiJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Calls the deal-state service (see DealIntelligence_CRM/scripts/serve.py).
 *
 * The difference from {@link DealAnalysisClient} is memory. That one reads a
 * single write-up in isolation, so anything an earlier meeting established but
 * this one didn't repeat falls back to a neutral default — and two of the
 * fields it defaults are one-hot columns, which the scorer accepts silently
 * rather than rejecting. A deal then gets a confident score from evidence that
 * quietly vanished.
 *
 * This service is handed the previous state as well as the notes, and returns
 * the state after this meeting: fields the notes moved are updated, everything
 * else carries forward untouched. It reports which fields it changed, and which
 * values it had to repair to fit the scorer's vocabulary.
 *
 * Three of the seventeen fields it returns are discarded by the caller —
 * total_meetings, lead_score and engagement_score are arithmetic the CRM
 * already does correctly and the model measurably does not (40% field accuracy
 * on lead_score, per the adapter's own provenance). See
 * {@link FeatureEngineeringService}, which recomputes all three.
 */
@Component
public class DealStateClient {

    private static final Logger log = LoggerFactory.getLogger(DealStateClient.class);

    private final RestClient restClient;
    private final boolean configured;

    public DealStateClient(
            @Value("${deal-state.base-url:}") String baseUrl,
            @Value("${deal-state.request-timeout-ms:180000}") long timeoutMs) {

        // Blank by default, so an installation that has not started the service
        // keeps the previous single-meeting behaviour instead of failing every
        // submission. Setting the URL is what turns this path on.
        this.configured = baseUrl != null && !baseUrl.isBlank();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(this.configured ? baseUrl : "http://127.0.0.1:8002")
                .requestFactory(requestFactory)
                .build();
    }

    public boolean isConfigured() {
        return configured;
    }

    /**
     * @param state         the seventeen fields after this meeting
     * @param changedFields which of them this meeting moved
     * @param repairs       values that had to be snapped or imputed to fit the
     *                      scorer's vocabulary — never empty-and-fine, always
     *                      worth logging, because an unrepaired bad one-hot
     *                      value scores as zero without raising
     * @param adapterLoaded false means the service answered from base weights
     *                      because no trained adapter was found; the reply is
     *                      well-formed but not fit for scoring
     */
    public record DealStateResult(
            Map<String, Object> state,
            Set<String> changedFields,
            List<String> repairs,
            boolean adapterLoaded,
            String modelVersion,
            String rawResponse,
            long latencyMs) {
    }

    /**
     * Returns null when the service is unconfigured, unreachable, or answered
     * with something unusable. The caller falls back to single-meeting
     * extraction rather than failing the submission.
     *
     * @param previousState the state after the previous meeting, or null/empty
     *                      for the first meeting on this deal
     */
    public DealStateResult update(Map<String, Object> previousState, MeetingOutput meeting) {
        if (!configured) {
            return null;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        // Omitted rather than sent null: the service reads an absent key as
        // "first meeting on this opportunity" and starts from its own defaults.
        if (previousState != null && !previousState.isEmpty()) {
            body.put("previous_state", previousState);
        }
        String notes = meetingNotes(meeting);
        if (notes.isBlank()) {
            log.warn("Meeting output {} has no written content — skipping deal-state update", meeting.getId());
            return null;
        }
        body.put("meeting_notes", notes);

        long start = System.currentTimeMillis();
        String raw;
        try {
            // Retrieved as text and parsed here rather than bound to a type.
            // Spring Boot 4 converts with Jackson 3, which cannot deserialise
            // into Jackson 2's JsonNode — and AiJson, which every other model
            // reply in this codebase goes through, is Jackson 2. One parser for
            // model output, not two.
            raw = restClient.post()
                    .uri("/v1/deal-state")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            // A 503 here is the service saying its model is still loading, which
            // is a normal state for the first minute after start-up.
            log.warn("Deal-state service unavailable for meeting output {}: {}", meeting.getId(), e.getMessage());
            return null;
        }
        long latency = System.currentTimeMillis() - start;

        JsonNode json = AiJson.parse(raw);
        if (json == null || !json.has("state") || !json.get("state").isObject()) {
            log.warn("Deal-state service returned no state for meeting output {}", meeting.getId());
            return null;
        }

        Map<String, Object> state = new LinkedHashMap<>();
        json.get("state").fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isNumber()) {
                state.put(entry.getKey(), value.numberValue());
            } else if (!value.isNull()) {
                state.put(entry.getKey(), value.asText());
            }
        });

        if (state.isEmpty()) {
            log.warn("Deal-state reply held an empty state for meeting output {}", meeting.getId());
            return null;
        }

        Set<String> changed = new LinkedHashSet<>(textList(json.get("changed_fields")));
        List<String> repairs = textList(json.get("repairs"));
        boolean adapterLoaded = json.path("adapter").asBoolean(false);
        String modelVersion = json.path("model_version").asText(null);

        if (!adapterLoaded) {
            // Loud on purpose: the reply is well-formed and will score, so
            // nothing downstream can tell that it came from untrained weights.
            log.warn("Deal-state answered meeting output {} from BASE weights — no trained adapter loaded. "
                    + "The resulting score is not production output.", meeting.getId());
        }
        if (!repairs.isEmpty()) {
            log.warn("Deal-state repaired {} value(s) for meeting output {}: {}",
                    repairs.size(), meeting.getId(), String.join("; ", repairs));
        }

        return new DealStateResult(state, changed, repairs, adapterLoaded, modelVersion,
                json.toString(), latency);
    }

    private List<String> textList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(element -> {
                if (!element.isNull()) {
                    values.add(element.asText());
                }
            });
        }
        return values;
    }

    /**
     * The write-up as free text.
     *
     * No deal name, no lead score and no meeting number, unlike the prompt
     * {@link DealAnalysisClient} builds — those live in the state this call also
     * sends, and repeating them in the notes would invite the model to treat
     * them as fresh evidence from this meeting.
     *
     * Empty sections are omitted rather than sent blank, so a field the
     * executive left alone reads as "not discussed" rather than as an explicit
     * negative that should move the state.
     */
    private String meetingNotes(MeetingOutput m) {
        List<String> lines = new ArrayList<>();
        addIfPresent(lines, "Meeting type", m.getMeetingType());
        addIfPresent(lines, "Participants", m.getParticipants());
        addIfPresent(lines, "Summary", m.getMeetingSummary());
        addIfPresent(lines, "Customer requirements", m.getCustomerRequirements());
        addIfPresent(lines, "Key discussion points", m.getKeyDiscussionPoints());
        addIfPresent(lines, "Customer questions", m.getCustomerQuestions());
        addIfPresent(lines, "Competitors mentioned", m.getCompetitorMentioned());
        addIfPresent(lines, "Objections raised", m.getObjections());
        addIfPresent(lines, "Budget discussion", m.getBudgetDiscussion());
        addIfPresent(lines, "Timeline", m.getTimeline());
        addIfPresent(lines, "Next steps", m.getNextSteps());
        addIfPresent(lines, "Executive remarks", m.getExecutiveRemarks());
        return String.join("\n", lines);
    }

    private void addIfPresent(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(label + ": " + value.trim());
        }
    }
}
