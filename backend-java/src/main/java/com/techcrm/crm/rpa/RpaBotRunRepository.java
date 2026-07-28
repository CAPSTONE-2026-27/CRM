package com.techcrm.crm.rpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RpaBotRunRepository extends JpaRepository<RpaBotRun, Long> {

    List<RpaBotRun> findTop100ByOrganizationIdOrderByStartedAtDesc(Long organizationId);

    List<RpaBotRun> findTop100ByOrganizationIdAndBotIdOrderByStartedAtDesc(Long organizationId, Long botId);
}
