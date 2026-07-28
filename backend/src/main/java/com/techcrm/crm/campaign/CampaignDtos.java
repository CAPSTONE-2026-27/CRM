package com.techcrm.crm.campaign;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public final class CampaignDtos {

    private CampaignDtos() {
    }

    public record CampaignRequest(
            @NotBlank String name,
            String channel,
            String goal,
            BigDecimal budget,
            Long ownerId,
            OffsetDateTime startDate,
            OffsetDateTime endDate,
            String segment,
            String region,
            Integer estimatedReach,
            String status,
            Integer sentCount,
            @Min(0) @Max(100) Integer openRatePct
    ) {
    }

    public record CampaignResponse(
            String id,
            String name,
            String channel,
            String goal,
            BigDecimal budget,
            String ownerId,
            OffsetDateTime startDate,
            OffsetDateTime endDate,
            String segment,
            String region,
            Integer estimatedReach,
            String status,
            Integer sentCount,
            Integer openRatePct,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public static CampaignResponse from(Campaign c) {
            return new CampaignResponse(
                    String.valueOf(c.getId()),
                    c.getName(),
                    c.getChannel(),
                    c.getGoal(),
                    c.getBudget(),
                    c.getOwnerId() == null ? null : String.valueOf(c.getOwnerId()),
                    c.getStartDate(),
                    c.getEndDate(),
                    c.getSegment(),
                    c.getRegion(),
                    c.getEstimatedReach(),
                    c.getStatus(),
                    c.getSentCount(),
                    c.getOpenRatePct(),
                    c.getCreatedAt(),
                    c.getUpdatedAt()
            );
        }
    }
}
