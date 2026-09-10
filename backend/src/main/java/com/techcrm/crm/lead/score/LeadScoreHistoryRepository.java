package com.techcrm.crm.lead.score;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LeadScoreHistoryRepository extends JpaRepository<LeadScoreHistory, Long> {

    /** The timeline: every movement for one lead, oldest first. */
    List<LeadScoreHistory> findByLeadIdAndOrganizationIdOrderByCreatedAtAsc(
            Long leadId, Long organizationId);

    /** The most recent movement, for reading the current state without
     *  loading a whole history. */
    Optional<LeadScoreHistory> findFirstByLeadIdAndOrganizationIdOrderByCreatedAtDesc(
            Long leadId, Long organizationId);

    /**
     * How many meetings this lead has been scored on.
     *
     * Counts rows with a meeting attached rather than all rows, so the INITIAL
     * row does not inflate "Meeting 1" into "Meeting 2".
     */
    long countByLeadIdAndOrganizationIdAndMeetingIdIsNotNull(Long leadId, Long organizationId);

    boolean existsByLeadIdAndOrganizationId(Long leadId, Long organizationId);
}
