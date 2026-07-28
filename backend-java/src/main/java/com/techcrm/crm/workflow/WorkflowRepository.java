package com.techcrm.crm.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowRepository extends JpaRepository<WorkflowDefinition, Long> {

    List<WorkflowDefinition> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    Optional<WorkflowDefinition> findByIdAndOrganizationId(Long id, Long organizationId);
}
