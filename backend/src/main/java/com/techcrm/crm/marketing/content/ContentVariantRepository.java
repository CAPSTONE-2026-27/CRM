package com.techcrm.crm.marketing.content;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ContentVariantRepository extends JpaRepository<ContentVariant, Long> {

    List<ContentVariant> findByCampaignIdOrderByChannelAscVariantAsc(Long campaignId);

    Optional<ContentVariant> findByIdAndCampaignId(Long id, Long campaignId);

    Optional<ContentVariant> findByCampaignIdAndChannelAndVariant(Long campaignId, ContentChannel channel, String variant);

    @Modifying
    @Query("delete from ContentVariant v where v.campaignId = :campaignId and v.variant = :variant")
    void deleteByCampaignIdAndVariant(@Param("campaignId") Long campaignId, @Param("variant") String variant);
}
