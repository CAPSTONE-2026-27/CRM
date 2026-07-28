package com.techcrm.crm.workflow;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.workflow.WorkflowDtos.WorkflowRequest;
import com.techcrm.crm.workflow.WorkflowDtos.WorkflowResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Service
public class WorkflowService {

    private final WorkflowRepository workflowRepository;

    public WorkflowService(WorkflowRepository workflowRepository) {
        this.workflowRepository = workflowRepository;
    }

    @Transactional(readOnly = true)
    public List<WorkflowResponse> list(AuthenticatedUser caller) {
        return workflowRepository.findByOrganizationIdOrderByCreatedAtDesc(caller.organizationId())
                .stream().map(WorkflowResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public WorkflowResponse get(AuthenticatedUser caller, Long id) {
        return WorkflowResponse.from(require(caller, id));
    }

    @Transactional
    public WorkflowResponse create(AuthenticatedUser caller, WorkflowRequest request) {
        WorkflowDefinition workflow = new WorkflowDefinition();
        workflow.setOrganizationId(caller.organizationId());
        workflow.setCreatedById(caller.userId());
        apply(workflow, request);
        return WorkflowResponse.from(workflowRepository.save(workflow));
    }

    @Transactional
    public WorkflowResponse update(AuthenticatedUser caller, Long id, WorkflowRequest request) {
        WorkflowDefinition workflow = require(caller, id);
        apply(workflow, request);
        return WorkflowResponse.from(workflowRepository.save(workflow));
    }

    /** Separate from update so the list screen's "Activate" button doesn't have
     *  to round-trip the whole definition, including its node list. */
    @Transactional
    public WorkflowResponse setActive(AuthenticatedUser caller, Long id, boolean active) {
        WorkflowDefinition workflow = require(caller, id);
        workflow.setActive(active);
        return WorkflowResponse.from(workflowRepository.save(workflow));
    }

    @Transactional
    public void delete(AuthenticatedUser caller, Long id) {
        workflowRepository.delete(require(caller, id));
    }

    private WorkflowDefinition require(AuthenticatedUser caller, Long id) {
        return workflowRepository.findByIdAndOrganizationId(id, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found"));
    }

    private void apply(WorkflowDefinition workflow, WorkflowRequest request) {
        workflow.setName(request.name());
        workflow.setTriggerEvent(request.triggerEvent());
        workflow.setScope(request.scope());
        workflow.setRunMode(request.runMode());
        if (request.isActive() != null) workflow.setActive(request.isActive());
        workflow.setNodes(request.nodes() == null ? new ArrayList<>() : request.nodes());
    }
}
