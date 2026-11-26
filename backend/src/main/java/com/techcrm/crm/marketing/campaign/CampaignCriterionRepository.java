package com.techcrm.crm.marketing.campaign;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CampaignCriterionRepository extends JpaRepository<CampaignCriterion, Long> {

    List<CampaignCriterion> findByCampaignIdOrderByPosition(Long campaignId);

    @Modifying
    @Query("delete from CampaignCriterion c where c.campaignId = :campaignId")
    void deleteByCampaignId(@Param("campaignId") Long campaignId);
}
