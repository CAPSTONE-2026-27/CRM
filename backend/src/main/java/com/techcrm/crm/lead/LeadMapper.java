package com.techcrm.crm.lead;

public final class LeadMapper {

    private LeadMapper() {
    }

    public static LeadResponse toResponse(Lead l) {
        return new LeadResponse(
                String.valueOf(l.getId()),
                l.getFullName(), l.getCompany(), l.getIndustry(), l.getEmployeeCount(),
                l.getEmail(), l.getPhone(), l.getProduct(), l.getEstimatedDealValue(),
                l.getProductQuantity(), l.getPurchaseTimeline(),
                l.getSourceChannel(), l.getCaptureMethod(), l.getNotes(),
                l.getAiScore(), l.getAiScoreLabel(), l.getAiScoreReason(),
                l.getStatus(), l.getAssignedToId() != null ? String.valueOf(l.getAssignedToId()) : null,

                l.getQualificationStatus(), l.getQualificationProbability(), l.getQualificationReasoning(),
                l.getAssignedAt(), l.getAssignmentStatus(),
                l.getContactStatus(), l.getContactStatusUpdatedAt(), l.getContactNotes(),
                l.getConvertedDealId() != null ? String.valueOf(l.getConvertedDealId()) : null, l.getConvertedAt(),

                l.getCreatedAt(), l.getUpdatedAt());
    }
}
