package com.techcrm.crm.marketing.execution;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageEventRepository extends JpaRepository<MessageEvent, Long> {

    List<MessageEvent> findByCampaignIdOrderByOccurredAtDescIdDesc(Long campaignId, Pageable page);
}
