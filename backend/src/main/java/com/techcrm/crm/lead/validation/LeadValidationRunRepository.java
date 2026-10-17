package com.techcrm.crm.lead.validation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeadValidationRunRepository extends JpaRepository<LeadValidationRun, UUID> {

    /** Organization-scoped like every other lookup in this application: a run
     *  belonging to another tenant is not found rather than refused. */
    Optional<LeadValidationRun> findByIdAndOrganizationId(UUID id, Long organizationId);

    /** Runs the poller still has to resolve. */
    List<LeadValidationRun> findByStateIn(List<String> states);

    /** The sweep. Completed runs are worth keeping only long enough to answer
     *  "why was this lead refused" while the question is still being asked. */
    @Modifying
    @Query("delete from LeadValidationRun r where r.startedAt < :cutoff")
    int deleteStartedBefore(@Param("cutoff") OffsetDateTime cutoff);
}
