package com.techcrm.crm.deal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;

/**
 * Calls the XGBoost deal-scoring service (see XgBoost/serve_api.py).
 *
 * The model is a Python artefact — an sklearn/XGBoost pickle whose encoders are
 * part of the bundle — so it is served over HTTP rather than reimplemented
 * here. Re-deriving its ordinal ordering and one-hot layout in Java would mean
 * two definitions of the feature contract, and a silent scoring drift the first
 * time they disagreed.
 *
 * Scoring never blocks saving a deal: an unreachable model returns null and the
 * deal is stored unscored, to be scored on a later edit.
 */
@Component
public class DealScoringClient {

    private static final Logger log = LoggerFactory.getLogger(DealScoringClient.class);

    private final RestClient restClient;
    private final boolean configured;

    public DealScoringClient(
            @Value("${deal-scoring.base-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${deal-scoring.request-timeout-ms:10000}") long timeoutMs) {

        this.configured = baseUrl != null && !baseUrl.isBlank();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));

        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    public record DealScoreResult(Double dealScore, String band, String action, String modelVersion) {
    }

    /**
     * Returns the model's verdict, or null when it can't produce one — the
     * service is down, or the deal has no scoring inputs filled in yet.
     */
    public DealScoreResult score(Deal deal) {
        if (!configured || !hasScoringInputs(deal)) {
            return null;
        }

        // Keys are the model's own feature names, which are snake_case and
        // lower-cased by its pipeline on ingest.
        Map<String, Object> payload = Map.ofEntries(
                Map.entry("total_meetings", orZero(deal.getTotalMeetings())),
                Map.entry("lead_score", orZero(deal.getLeadScore())),
                Map.entry("customer_sentiment", orEmpty(deal.getCustomerSentiment())),
                Map.entry("buying_intent", orEmpty(deal.getBuyingIntent())),
                Map.entry("relationship_strength", orZero(deal.getRelationshipStrength())),
                Map.entry("budget_status", orEmpty(deal.getBudgetStatus())),
                Map.entry("decision_maker_involvement", orEmpty(deal.getDecisionMakerInvolvement())),
                Map.entry("customer_urgency", orEmpty(deal.getCustomerUrgency())),
                Map.entry("main_objections", orDefault(deal.getMainObjections(), "No Objections")),
                Map.entry("product_interest_level", orEmpty(deal.getProductInterestLevel())),
                Map.entry("meeting_outcome", orEmpty(deal.getMeetingOutcome())),
                Map.entry("customer_requirements", orEmpty(deal.getCustomerRequirements())),
                Map.entry("risk_factors", orDefault(deal.getRiskFactors(), "No Risk Identified")),
                Map.entry("competitor_mention", orDefault(deal.getCompetitorMention(), "No")),
                Map.entry("engagement_score", orZero(deal.getEngagementScore())),
                Map.entry("implementation_readiness", orEmpty(deal.getImplementationReadiness())),
                Map.entry("upsell_opportunity", orDefault(deal.getUpsellOpportunity(), "No")));

        try {
            Map<String, Object> body = restClient.post()
                    .uri("/score")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    // A 400 means we sent a value the model has never seen. That is
                    // our bug, not a transient fault, so log it loudly rather than
                    // retrying it forever.
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        log.warn("Deal scoring rejected the payload ({}). Check the form's allowed values "
                                + "against the model's /schema endpoint.", res.getStatusCode());
                        throw new RestClientException("Deal scoring rejected the payload");
                    })
                    .body(Map.class);

            if (body == null || body.get("deal_score") == null) {
                return null;
            }
            return new DealScoreResult(
                    ((Number) body.get("deal_score")).doubleValue(),
                    (String) body.get("band"),
                    (String) body.get("action"),
                    (String) body.get("model_version"));
        } catch (RestClientException e) {
            log.warn("Deal scoring unavailable, leaving deal '{}' unscored: {}", deal.getName(), e.getMessage());
            return null;
        }
    }

    /** The model needs the full picture; a deal with none of it isn't scoreable. */
    private boolean hasScoringInputs(Deal deal) {
        return deal.getCustomerSentiment() != null
                && deal.getBuyingIntent() != null
                && deal.getBudgetStatus() != null
                && deal.getMeetingOutcome() != null;
    }

    private Object orZero(Number value) {
        return value == null ? 0 : value;
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
