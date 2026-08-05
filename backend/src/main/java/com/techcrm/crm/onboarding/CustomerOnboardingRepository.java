package com.techcrm.crm.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerOnboardingRepository extends JpaRepository<CustomerOnboarding, Long> {

    Optional<CustomerOnboarding> findByDealIdAndOrganizationId(Long dealId, Long organizationId);

    List<CustomerOnboarding> findByOrganizationIdOrderByInitiatedAtDesc(Long organizationId);
}
