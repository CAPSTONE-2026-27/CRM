package com.techcrm.crm.account;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Request/response payloads for the accounts module. Grouped in one file
 *  because they are small records that only ever change together. */
public final class AccountDtos {

    private AccountDtos() {
    }

    public record AccountRequest(
            @NotBlank String name,
            String industry,
            BigDecimal annualRevenue,
            String employeeCount,
            String billingAddress,
            Long parentAccountId,
            Long ownerId,
            BigDecimal relationshipValue,
            @Min(0) @Max(100) Integer aiSentimentScore,
            Boolean emailIntegrationEnabled,
            Boolean telephonyIntegrationEnabled,
            Boolean docRepoSyncEnabled
    ) {
    }

    public record AccountResponse(
            String id,
            String name,
            String industry,
            BigDecimal annualRevenue,
            String employeeCount,
            String billingAddress,
            String parentAccountId,
            String ownerId,
            BigDecimal relationshipValue,
            Integer aiSentimentScore,
            boolean emailIntegrationEnabled,
            boolean telephonyIntegrationEnabled,
            boolean docRepoSyncEnabled,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        // Ids are serialised as strings to match the rest of this API, which
        // the frontend treats as opaque identifiers rather than numbers.
        public static AccountResponse from(Account a) {
            return new AccountResponse(
                    String.valueOf(a.getId()),
                    a.getName(),
                    a.getIndustry(),
                    a.getAnnualRevenue(),
                    a.getEmployeeCount(),
                    a.getBillingAddress(),
                    a.getParentAccountId() == null ? null : String.valueOf(a.getParentAccountId()),
                    a.getOwnerId() == null ? null : String.valueOf(a.getOwnerId()),
                    a.getRelationshipValue(),
                    a.getAiSentimentScore(),
                    a.isEmailIntegrationEnabled(),
                    a.isTelephonyIntegrationEnabled(),
                    a.isDocRepoSyncEnabled(),
                    a.getCreatedAt(),
                    a.getUpdatedAt()
            );
        }
    }
}
