package com.techcrm.crm.lead;

/**
 * The scoring model's verdict on a lead.
 *
 * Score and qualification come from one model call rather than two: a lead
 * scored 85 and simultaneously marked unqualified is incoherent, and two
 * independent calls have no way to prevent that.
 *
 * @param qualificationStatus      QUALIFIED or UNQUALIFIED
 * @param qualificationProbability 0-100 likelihood that this lead is worth working
 */
record AiScoreResult(
        Integer score,
        String label,
        String reason,
        String qualificationStatus,
        Double qualificationProbability,
        String qualificationReasoning
) {
}
