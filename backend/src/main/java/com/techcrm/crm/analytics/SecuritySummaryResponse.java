package com.techcrm.crm.analytics;

/**
 * Security & audit headline figures, last 24 hours, organization-scoped.
 *
 * mfaCoveragePct is reported as 0 because the product has no multi-factor
 * authentication yet — no user can have it enrolled. It is a true figure, not a
 * placeholder, and will become a real ratio once MFA enrolment is stored.
 */
public record SecuritySummaryResponse(
        long auditEvents24h,
        long failedLogins24h,
        int mfaCoveragePct
) {
}
