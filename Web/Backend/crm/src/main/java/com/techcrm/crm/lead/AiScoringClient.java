package com.techcrm.crm.lead;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class AiScoringClient {

    private static final Logger log = LoggerFactory.getLogger(AiScoringClient.class);

    private final RestClient restClient;

    public AiScoringClient(@Value("${ai.service.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public AiScoreResult score(Lead lead) {
        AiScoreRequest request = new AiScoreRequest(
                lead.getFullName(), lead.getCompany(), lead.getIndustry(),
                lead.getEmployeeCount(), lead.getProduct(), lead.getEstimatedDealValue(),
                lead.getSourceChannel(), lead.getNotes());

        try {
            return restClient.post()
                    .uri("/score")
                    .body(request)
                    .retrieve()
                    .body(AiScoreResult.class);
        } catch (RestClientException e) {
            log.warn("AI scoring service unavailable, leaving lead '{}' unscored: {}",
                    lead.getFullName(), e.getMessage());
            return null;
        }
    }
}
