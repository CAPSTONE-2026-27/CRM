package com.techcrm.crm.campaign;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    List<Campaign> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    Optional<Campaign> findByIdAndOrganizationId(Long id, Long organizationId);
}
