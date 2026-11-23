package com.techcrm.crm.marketing.approval;

import com.techcrm.crm.marketing.approval.MarketingApproval.Kind;
import com.techcrm.crm.marketing.approval.MarketingApproval.Status;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MarketingApprovalRepository extends JpaRepository<MarketingApproval, Long> {

    List<MarketingApproval> findByOrganizationIdAndStatusOrderBySubmittedAtAsc(Long organizationId, Status status);

    List<MarketingApproval> findByCampaignIdOrderBySubmittedAtDesc(Long campaignId);

    Optional<MarketingApproval> findFirstByCampaignIdAndKindAndStatus(Long campaignId, Kind kind, Status status);

    long countByOrganizationIdAndStatus(Long organizationId, Status status);
}
