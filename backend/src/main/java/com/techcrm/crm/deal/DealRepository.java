package com.techcrm.crm.deal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DealRepository extends JpaRepository<Deal, Long> {

    List<Deal> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    Optional<Deal> findByIdAndOrganizationId(Long id, Long organizationId);
}
