package com.techcrm.crm.lead;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface LeadRepository extends JpaRepository<Lead, Long>, JpaSpecificationExecutor<Lead> {

    List<Lead> findByOrganizationId(Long organizationId);

    List<Lead> findByOrganizationIdAndAssignedToId(Long organizationId, Long assignedToId);

    Optional<Lead> findByIdAndOrganizationId(Long id, Long organizationId);

    long countByAssignedToId(Long assignedToId);

    // Narrow candidate sets for duplicate detection — the actual
    // name+email+company comparison (with null-safe equality) happens in
    // LeadService, since a DB-level "= NULL" match can't express "both
    // blank counts as equal" the way a derived query would need it to.
    List<Lead> findByOrganizationIdAndEmailIgnoreCase(Long organizationId, String email);

    List<Lead> findByOrganizationIdAndFullNameIgnoreCase(Long organizationId, String fullName);

    /** Candidates for phone duplicate detection.
     *
     *  Narrowed in the database only by organization; the digits-only
     *  comparison happens in {@link LeadService}, for the same reason the
     *  name+company one does — the same number is written "+91 90000 00000",
     *  "09000000000" and "9000000000" by three different representatives, and
     *  no derived query expresses "equal once punctuation is stripped"
     *  portably. */
    List<Lead> findByOrganizationIdAndPhoneIsNotNull(Long organizationId);
}
