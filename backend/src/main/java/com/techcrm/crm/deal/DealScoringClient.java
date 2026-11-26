package com.techcrm.crm.deal;

import com.techcrm.crm.dealflow.DealParameters;
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

import static com.techcrm.crm.dealflow.DealParameters.BUDGET_STATUS;
import static com.techcrm.crm.dealflow.DealParameters.BUYING_INTENT;
import static com.techcrm.crm.dealflow.DealParameters.COMPETITOR_MENTIONS;
import static com.techcrm.crm.dealflow.DealParameters.CUSTOMER_REQUIREMENTS;
import static com.techcrm.crm.dealflow.DealParameters.CUSTOMER_SENTIMENT;
import static com.techcrm.crm.dealflow.DealParameters.CUSTOMER_URGENCY;
import static com.techcrm.crm.dealflow.DealParameters.DECISION_MAKER_INVOLVEMENT;
import static com.techcrm.crm.dealflow.DealParameters.IMPLEMENTATION_READINESS;
import static com.techcrm.crm.dealflow.DealParameters.MEETING_OUTCOME;
import static com.techcrm.crm.dealflow.DealParameters.PRODUCT_INTEREST_LEVEL;
import static com.techcrm.crm.dealflow.DealParameters.RISK_FACTORS;
import static com.techcrm.crm.dealflow.DealParameters.UPSELL_OPPORTUNITY;

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

    public record DealScoreResult(Double dealScore, Double winProbability, String band, String action,
                                  String modelVersion) {
    }

    /**
     * Scores a pre-built input map — the path used by the deal flow, where the
     * 17 values come from the feature engineering layer rather than from a
     * form. Returns null on any failure, same contract as {@link #score(Deal)}.
     */
    public DealScoreResult scoreFeatures(Map<String, Object> modelInputs) {
        if (!configured || modelInputs == null || modelInputs.isEmpty()) {
            return null;
        }
        return post(modelInputs, "engineered features");
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
        //
        // Every categorical falls back to the neutral default the deal flow uses
        // (DealParameters.DEFAULTS), never to "". The scorer runs in strict mode:
        // an empty ordinal value is a 400 and the deal is silently left unscored,
        // and an empty one-hot value scores every requirement/risk feature as zero.
        Map<String, Object> payload = Map.ofEntries(
                Map.entry("total_meetings", orZero(deal.getTotalMeetings())),
                Map.entry("lead_score", orZero(deal.getLeadScore())),
                Map.entry("customer_sentiment", categorical(deal.getCustomerSentiment(), CUSTOMER_SENTIMENT)),
                Map.entry("buying_intent", categorical(deal.getBuyingIntent(), BUYING_INTENT)),
                Map.entry("relationship_strength", orZero(deal.getRelationshipStrength())),
                Map.entry("budget_status", categorical(deal.getBudgetStatus(), BUDGET_STATUS)),
                Map.entry("decision_maker_involvement",
                        categorical(deal.getDecisionMakerInvolvement(), DECISION_MAKER_INVOLVEMENT)),
                Map.entry("customer_urgency", categorical(deal.getCustomerUrgency(), CUSTOMER_URGENCY)),
                Map.entry("main_objections", orDefault(deal.getMainObjections(), "No Objections")),
                Map.entry("product_interest_level",
                        categorical(deal.getProductInterestLevel(), PRODUCT_INTEREST_LEVEL)),
                Map.entry("meeting_outcome", categorical(deal.getMeetingOutcome(), MEETING_OUTCOME)),
                Map.entry("customer_requirements",
                        categorical(deal.getCustomerRequirements(), CUSTOMER_REQUIREMENTS)),
                Map.entry("risk_factors", categorical(deal.getRiskFactors(), RISK_FACTORS)),
                Map.entry("competitor_mention", categorical(deal.getCompetitorMention(), COMPETITOR_MENTIONS)),
                Map.entry("engagement_score", orZero(deal.getEngagementScore())),
                Map.entry("implementation_readiness",
                        categorical(deal.getImplementationReadiness(), IMPLEMENTATION_READINESS)),
                Map.entry("upsell_opportunity", categorical(deal.getUpsellOpportunity(), UPSELL_OPPORTUNITY)));

        return post(payload, "deal '" + deal.getName() + "'");
    }

    @SuppressWarnings("unchecked")
    private DealScoreResult post(Map<String, Object> payload, String subject) {
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
                        log.warn("Deal scoring rejected the payload ({}). Check the allowed values "
                                + "against the model's /schema endpoint.", res.getStatusCode());
                        throw new RestClientException("Deal scoring rejected the payload");
                    })
                    .body(Map.class);

            if (body == null || body.get("deal_score") == null) {
                return null;
            }
            Object winProbability = body.get("win_probability");
            return new DealScoreResult(
                    ((Number) body.get("deal_score")).doubleValue(),
                    winProbability == null ? null : ((Number) winProbability).doubleValue() * 100.0,
                    (String) body.get("band"),
                    (String) body.get("action"),
                    (String) body.get("model_version"));
        } catch (RestClientException e) {
            log.warn("Deal scoring unavailable, leaving {} unscored: {}", subject, e.getMessage());
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

    private String categorical(String value, String parameter) {
        return orDefault(value, DealParameters.DEFAULTS.get(parameter));
    }

    private String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
