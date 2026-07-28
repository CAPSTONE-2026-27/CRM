package com.techcrm.crm.meeting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.List;

/**
 * Asks the AI service to summarise a meeting and re-score the lead from the
 * rep's notes. Mirrors {@code AiScoringClient}: the same external service,
 * and an unavailable model degrades to null rather than failing the request —
 * the rep can still write their own summary and set the score by hand.
 */
@Component
public class MeetingAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(MeetingAnalysisClient.class);

    private final RestClient restClient;

    public MeetingAnalysisClient(@Value("${ai.service.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    record MeetingAnalysisRequest(
            String fullName,
            String company,
            String industry,
            String product,
            BigDecimal estimatedDealValue,
            String notes,
            Integer previousScore,
            String meetingDate,
            String meetingTime,
            String meetingOutput
    ) {
    }

    record MeetingAnalysisResult(String summary, Integer score, String label, List<String> reasons) {
    }

    public MeetingAnalysisResult analyze(MeetingAnalysisRequest request) {
        try {
            return restClient.post()
                    .uri("/meeting-analysis")
                    .body(request)
                    .retrieve()
                    .body(MeetingAnalysisResult.class);
        } catch (RestClientException e) {
            log.warn("Meeting analysis service unavailable for '{}': {}", request.fullName(), e.getMessage());
            return null;
        }
    }
}
