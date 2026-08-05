package com.techcrm.crm.dealflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DealAnalysisRepository extends JpaRepository<DealAnalysis, Long> {

    Optional<DealAnalysis> findByMeetingOutputIdAndOrganizationId(Long meetingOutputId, Long organizationId);

    List<DealAnalysis> findByDealIdAndOrganizationIdOrderByCreatedAtDesc(Long dealId, Long organizationId);
}
