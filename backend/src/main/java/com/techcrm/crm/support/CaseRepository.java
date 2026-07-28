package com.techcrm.crm.support;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CaseRepository extends JpaRepository<CaseRecord, Long> {

    List<CaseRecord> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    Optional<CaseRecord> findByIdAndOrganizationId(Long id, Long organizationId);

    /** Highest case number issued so far for an organization; null when it has
     *  no cases yet. Used to allocate the next per-org case number. */
    @Query("SELECT MAX(c.caseNumber) FROM CaseRecord c WHERE c.organizationId = :organizationId")
    Integer findMaxCaseNumber(@Param("organizationId") Long organizationId);
}
