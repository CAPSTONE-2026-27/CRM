package com.techcrm.crm.deal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public final class DealDtos {

    private DealDtos() {
    }

    public record DealRequest(
            @NotBlank String name,
            @NotNull Long accountId,
            @NotNull BigDecimal value,
            String currency,
            OffsetDateTime expectedCloseDate,
            String stage,
            @Min(0) @Max(100) Integer probability,
            Long ownerId,
            String forecastCategory,
            BigDecimal weightedForecastValue,
            BigDecimal bestCaseValue,
            Boolean autoGenerateProposal,
            Boolean pushToErpOnClose
    ) {
    }

    /** Partial update used by the pipeline board, which drags a card between
     *  columns and only ever changes the stage. */
    public record DealStageRequest(@NotBlank String stage) {
    }

    public record DealResponse(
            String id,
            String name,
            String accountId,
            BigDecimal value,
            String currency,
            OffsetDateTime expectedCloseDate,
            String stage,
            Integer probability,
            String ownerId,
            String forecastCategory,
            BigDecimal weightedForecastValue,
            BigDecimal bestCaseValue,
            boolean autoGenerateProposal,
            boolean pushToErpOnClose,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public static DealResponse from(Deal d) {
            return new DealResponse(
                    String.valueOf(d.getId()),
                    d.getName(),
                    String.valueOf(d.getAccountId()),
                    d.getValue(),
                    d.getCurrency(),
                    d.getExpectedCloseDate(),
                    d.getStage(),
                    d.getProbability(),
                    d.getOwnerId() == null ? null : String.valueOf(d.getOwnerId()),
                    d.getForecastCategory(),
                    d.getWeightedForecastValue(),
                    d.getBestCaseValue(),
                    d.isAutoGenerateProposal(),
                    d.isPushToErpOnClose(),
                    d.getCreatedAt(),
                    d.getUpdatedAt()
            );
        }
    }
}
