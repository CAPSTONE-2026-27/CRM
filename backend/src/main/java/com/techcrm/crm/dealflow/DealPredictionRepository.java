package com.techcrm.crm.dealflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DealPredictionRepository extends JpaRepository<DealPrediction, Long> {

    List<DealPrediction> findByDealIdAndOrganizationIdOrderByPredictedAtDesc(Long dealId, Long organizationId);

    Optional<DealPrediction> findFirstByDealIdAndOrganizationIdOrderByPredictedAtDesc(Long dealId, Long organizationId);

    Optional<DealPrediction> findByIdAndOrganizationId(Long id, Long organizationId);

    /** The prediction produced by one specific feature set. Scoped this way
     *  rather than "the deal's newest", so a submission whose scoring failed
     *  reports no prediction instead of inheriting the previous meeting's. */
    Optional<DealPrediction> findByFeatureSetIdAndOrganizationId(Long featureSetId, Long organizationId);
}
