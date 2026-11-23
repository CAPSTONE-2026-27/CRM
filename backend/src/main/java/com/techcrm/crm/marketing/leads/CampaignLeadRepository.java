package com.techcrm.crm.marketing.leads;

import com.techcrm.crm.marketing.leads.CampaignLead.ApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CampaignLeadRepository extends JpaRepository<CampaignLead, Long> {

    List<CampaignLead> findByCampaignIdOrderByAiScoreDesc(Long campaignId);

    List<CampaignLead> findByCampaignIdAndApprovalStatus(Long campaignId, ApprovalStatus status);

    Optional<CampaignLead> findByIdAndCampaignId(Long id, Long campaignId);

    List<CampaignLead> findByCampaignIdAndIdIn(Long campaignId, Collection<Long> ids);

    Optional<CampaignLead> findByCampaignIdAndLeadId(Long campaignId, Long leadId);

    long countByCampaignId(Long campaignId);

    long countByCampaignIdAndApprovalStatus(Long campaignId, ApprovalStatus status);

    /** Leads the AI (or an override) marked eligible that nobody has decided on yet
     *  — the ones that block the campaign. */
    @Query("select count(l) from CampaignLead l where l.campaignId = :campaignId "
            + "and l.approvalStatus = com.techcrm.crm.marketing.leads.CampaignLead.ApprovalStatus.PENDING "
            + "and coalesce(l.overrideEligibility, l.aiEligibility) = "
            + "com.techcrm.crm.marketing.leads.CampaignLead.Eligibility.ELIGIBLE")
    long countEligiblePending(@Param("campaignId") Long campaignId);

    @Query("select count(l) from CampaignLead l where l.campaignId = :campaignId "
            + "and coalesce(l.overrideEligibility, l.aiEligibility) = "
            + "com.techcrm.crm.marketing.leads.CampaignLead.Eligibility.ELIGIBLE")
    long countEligible(@Param("campaignId") Long campaignId);

    /** Per campaign: [campaignId, eligible, approved, pendingEligible]. */
    @Query("select l.campaignId, "
            + "sum(case when coalesce(l.overrideEligibility, l.aiEligibility) = "
            + "com.techcrm.crm.marketing.leads.CampaignLead.Eligibility.ELIGIBLE then 1 else 0 end), "
            + "sum(case when l.approvalStatus = com.techcrm.crm.marketing.leads.CampaignLead.ApprovalStatus.APPROVED then 1 else 0 end), "
            + "sum(case when l.approvalStatus = com.techcrm.crm.marketing.leads.CampaignLead.ApprovalStatus.PENDING "
            + "and coalesce(l.overrideEligibility, l.aiEligibility) = "
            + "com.techcrm.crm.marketing.leads.CampaignLead.Eligibility.ELIGIBLE then 1 else 0 end) "
            + "from CampaignLead l where l.campaignId in :campaignIds group by l.campaignId")
    List<Object[]> countsByCampaign(@Param("campaignIds") Collection<Long> campaignIds);

    @Modifying
    @Query("delete from CampaignLead l where l.campaignId = :campaignId")
    void deleteByCampaignId(@Param("campaignId") Long campaignId);
}
