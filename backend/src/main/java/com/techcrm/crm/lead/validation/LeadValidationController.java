package com.techcrm.crm.lead.validation;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.lead.validation.LeadValidationDtos.DuplicateCheckRequest;
import com.techcrm.crm.lead.validation.LeadValidationDtos.ProgressRequest;
import com.techcrm.crm.lead.validation.LeadValidationDtos.ReportVerdictRequest;
import com.techcrm.crm.lead.validation.LeadValidationDtos.DuplicateCheckResponse;
import com.techcrm.crm.lead.validation.LeadValidationDtos.StartValidationRequest;
import com.techcrm.crm.lead.validation.LeadValidationDtos.StartValidationResponse;
import com.techcrm.crm.lead.validation.LeadValidationDtos.ValidationStatusResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Validating a lead before it is created.
 *
 * Two audiences. The browser starts a run and then polls it, because the check
 * takes seconds and the request that starts it cannot also carry its result.
 * The automation platform calls the duplicate endpoint part-way through its own
 * run, which is also how this API learns how far the automation has got.
 *
 * Nothing here creates a lead. A caller with a passing verdict posts to
 * {@code POST /api/leads} itself, so validation and creation stay separable —
 * a lead may still be created without validation when the service is
 * unavailable, which is the behaviour this feature is careful to preserve.
 */
@RestController
@RequestMapping("/api/leads")
public class LeadValidationController {

    private final LeadValidationService validationService;

    public LeadValidationController(LeadValidationService validationService) {
        this.validationService = validationService;
    }

    /**
     * Starts a validation run.
     *
     * <b>202</b> with the run's identifier. Answers in about a second: the
     * external process is started, not awaited. Poll the status endpoint.
     */
    @PostMapping("/validate/start")
    public ResponseEntity<StartValidationResponse> start(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @Valid @RequestBody StartValidationRequest request) {

        UUID id = validationService.start(caller, request);
        return ResponseEntity.accepted().body(new StartValidationResponse(id.toString()));
    }

    /**
     * Where a run has got to, and its verdict once it has one.
     *
     * <b>200</b> always while the run exists — a run that failed or timed out
     * is a result, not an error, and carries a verdict permitting creation with
     * a warning. <b>404</b> when the run is unknown to this organization.
     */
    @GetMapping("/validate/{validationId}/status")
    public ValidationStatusResponse status(@AuthenticationPrincipal AuthenticatedUser caller,
                                           @PathVariable String validationId) {
        return validationService.status(caller, parse(validationId));
    }

    /**
     * Whether this lead duplicates one already held. Called by the automation.
     *
     * Read-only and organization-scoped. The rule lives in the backend rather
     * than in the automation so that every path — this endpoint, CSV import,
     * the paste-an-email flow — reaches the same answer.
     */
    /**
     * The automation reporting what it concluded. Called by the bot when it
     * finishes.
     *
     * <b>202</b> always. A report for an unknown run, or for one already
     * finished, is accepted and ignored — the second is a retry, and refusing
     * it would only make the automation retry again.
     */
    @PostMapping("/validate/{validationId}/verdict")
    public ResponseEntity<Void> verdict(@AuthenticationPrincipal AuthenticatedUser caller,
                                        @PathVariable String validationId,
                                        @Valid @RequestBody ReportVerdictRequest request) {
        try {
            validationService.recordVerdict(caller, UUID.fromString(validationId.trim()), request);
        } catch (IllegalArgumentException e) {
            // Not a run id at all. Nothing to record, and nothing the
            // automation could usefully do about it either.
        }
        return ResponseEntity.accepted().build();
    }

    /**
     * The automation announcing which step it has reached. Called by the bot.
     *
     * <b>202</b> always, even for an unknown run or an unrecognised step: this
     * is a progress hint, and failing a validation because a progress call went
     * astray would be absurd.
     */
    @PostMapping("/validate/progress")
    public ResponseEntity<Void> progress(@AuthenticationPrincipal AuthenticatedUser caller,
                                         @Valid @RequestBody ProgressRequest request) {
        validationService.recordProgress(caller, request.validationId(), request.step());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/duplicate-check")
    public DuplicateCheckResponse duplicateCheck(@AuthenticationPrincipal AuthenticatedUser caller,
                                                 @Valid @RequestBody DuplicateCheckRequest request) {
        return validationService.duplicateCheck(caller, request);
    }

    private UUID parse(String validationId) {
        try {
            return UUID.fromString(validationId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Validation run not found");
        }
    }
}
