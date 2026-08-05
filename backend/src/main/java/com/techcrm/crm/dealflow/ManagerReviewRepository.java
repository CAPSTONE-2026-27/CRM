package com.techcrm.crm.dealflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ManagerReviewRepository extends JpaRepository<ManagerReview, Long> {

    List<ManagerReview> findByDealIdAndOrganizationIdOrderByCreatedAtDesc(Long dealId, Long organizationId);
}
