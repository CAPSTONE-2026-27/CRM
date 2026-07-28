package com.techcrm.crm.rpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RpaBotRepository extends JpaRepository<RpaBot, Long> {

    List<RpaBot> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    Optional<RpaBot> findByIdAndOrganizationId(Long id, Long organizationId);

    Optional<RpaBot> findFirstByOrganizationIdAndName(Long organizationId, String name);

    /** Every organization that has this bot registered — used by the scheduled
     *  sweep, which runs per-tenant rather than for one authenticated caller. */
    List<RpaBot> findByName(String name);
}
