package com.techcrm.crm.dealflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FeatureSetRepository extends JpaRepository<FeatureSet, Long> {

    Optional<FeatureSet> findByAnalysisId(Long analysisId);

    /** The deal's most recent model inputs — the state the next meeting starts
     *  from. Ordered by id rather than created_at because two feature sets
     *  written inside the same transaction can share a timestamp, and the later
     *  insert is always the later state. */
    Optional<FeatureSet> findFirstByDealIdAndOrganizationIdOrderByIdDesc(Long dealId, Long organizationId);
}
