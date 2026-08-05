package com.techcrm.crm.dealflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FeatureSetRepository extends JpaRepository<FeatureSet, Long> {

    Optional<FeatureSet> findByAnalysisId(Long analysisId);
}
