package com.techcrm.crm.workflow;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.workflow.WorkflowDtos.WorkflowRequest;
import com.techcrm.crm.workflow.WorkflowDtos.WorkflowResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @GetMapping
    public List<WorkflowResponse> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return workflowService.list(caller);
    }

    @GetMapping("/{id}")
    public WorkflowResponse get(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        return workflowService.get(caller, id);
    }

    @PostMapping
    public ResponseEntity<WorkflowResponse> create(@AuthenticationPrincipal AuthenticatedUser caller,
                                                   @Valid @RequestBody WorkflowRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workflowService.create(caller, request));
    }

    @PutMapping("/{id}")
    public WorkflowResponse update(@AuthenticationPrincipal AuthenticatedUser caller,
                                   @PathVariable Long id,
                                   @Valid @RequestBody WorkflowRequest request) {
        return workflowService.update(caller, id, request);
    }

    @PostMapping("/{id}/activate")
    public WorkflowResponse activate(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        return workflowService.setActive(caller, id, true);
    }

    @PostMapping("/{id}/deactivate")
    public WorkflowResponse deactivate(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        return workflowService.setActive(caller, id, false);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        workflowService.delete(caller, id);
        return ResponseEntity.noContent().build();
    }
}
