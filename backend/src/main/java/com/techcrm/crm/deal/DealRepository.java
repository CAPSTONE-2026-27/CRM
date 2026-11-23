package com.techcrm.crm.deal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DealRepository extends JpaRepository<Deal, Long> {

    List<Deal> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    Optional<Deal> findByIdAndOrganizationId(Long id, Long organizationId);

    /* Owner-scoped variants, used through DealAccess for roles that may only
     * see the deals they own. */

    List<Deal> findByOrganizationIdAndOwnerIdOrderByCreatedAtDesc(Long organizationId, Long ownerId);

    Optional<Deal> findByIdAndOrganizationIdAndOwnerId(Long id, Long organizationId, Long ownerId);
}
