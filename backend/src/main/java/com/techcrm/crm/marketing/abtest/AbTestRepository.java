package com.techcrm.crm.marketing.abtest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface AbTestRepository extends JpaRepository<AbTest, Long> {

    Optional<AbTest> findByCampaignId(Long campaignId);

    List<AbTest> findByStatusAndEndsAtLessThanEqual(AbTest.Status status, OffsetDateTime at);
}
