package com.techcrm.crm.lead.score;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LeadAiAnalysisRepository extends JpaRepository<LeadAiAnalysis, Long> {

    /** Every reading for one lead, newest first — the AI insight panel. */
    List<LeadAiAnalysis> findByLeadIdAndOrganizationIdOrderByCreatedAtDesc(
            Long leadId, Long organizationId);

    /** The latest reading, which is what the lead detail screen shows. */
    Optional<LeadAiAnalysis> findFirstByLeadIdAndOrganizationIdOrderByCreatedAtDesc(
            Long leadId, Long organizationId);

    Optional<LeadAiAnalysis> findByMeetingIdAndOrganizationId(Long meetingId, Long organizationId);
}
