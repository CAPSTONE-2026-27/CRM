package com.techcrm.crm.marketing.activity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CampaignActivityRepository extends JpaRepository<CampaignActivity, Long> {

    List<CampaignActivity> findByCampaignIdOrderByOccurredAtDescIdDesc(Long campaignId);

    List<CampaignActivity> findByOrganizationIdOrderByOccurredAtDescIdDesc(Long organizationId, Pageable page);
}
