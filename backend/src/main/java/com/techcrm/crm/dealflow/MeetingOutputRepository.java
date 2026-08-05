package com.techcrm.crm.dealflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Every finder is scoped by organization_id — a repository method that takes
 *  only an id is a cross-tenant read waiting to be called from the wrong place. */
public interface MeetingOutputRepository extends JpaRepository<MeetingOutput, Long> {

    List<MeetingOutput> findByDealIdAndOrganizationIdOrderByVersionDesc(Long dealId, Long organizationId);

    Optional<MeetingOutput> findByIdAndOrganizationId(Long id, Long organizationId);

    Optional<MeetingOutput> findFirstByDealIdAndOrganizationIdOrderByVersionDesc(Long dealId, Long organizationId);

    long countByDealIdAndOrganizationId(Long dealId, Long organizationId);
}
