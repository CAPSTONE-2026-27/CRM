package com.techcrm.crm.dealflow;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.dealflow.DealFlowDtos.DealWorkspace;
import com.techcrm.crm.dealflow.DealFlowDtos.ManagerReviewRequest;
import com.techcrm.crm.dealflow.DealFlowDtos.ManagerReviewResponse;
import com.techcrm.crm.dealflow.DealFlowDtos.MeetingOutputDetail;
import com.techcrm.crm.dealflow.DealFlowDtos.MeetingOutputRequest;
import com.techcrm.crm.onboarding.CustomerOnboarding;
import com.techcrm.crm.onboarding.CustomerOnboardingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The deal analysis pipeline's API.
 *
 * <pre>
 * GET  /api/deals/{id}/workspace          the whole flow state for one deal
 * POST /api/deals/{id}/meeting-outputs    submit a meeting write-up; runs the
 *                                         LLM -> features -> XGBoost chain and
 *                                         returns the resulting prediction
 * GET  /api/deals/{id}/meeting-outputs    the versioned history
 * POST /api/deals/{id}/review             a manager's approve/reject/override
 * GET  /api/deals/{id}/onboarding         the onboarding record, once won
 * PATCH /api/deals/{id}/onboarding        advance the onboarding status
 * GET  /api/deal-flow/parameters          the extraction vocabulary, for the UI
 * </pre>
 */
@RestController
public class DealFlowController {

    private final DealFlowService dealFlowService;
    private final CustomerOnboardingService onboardingService;

    public DealFlowController(DealFlowService dealFlowService, CustomerOnboardingService onboardingService) {
        this.dealFlowService = dealFlowService;
        this.onboardingService = onboardingService;
    }

    @GetMapping("/api/deals/{dealId}/workspace")
    public DealWorkspace workspace(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long dealId) {
        return dealFlowService.workspace(caller, dealId);
    }

    /**
     * Deal flow steps 4-8 in one call.
     *
     * Synchronous by design: the executive who wrote the meeting up is waiting
     * to see what the model made of it, and the analysis takes seconds, not
     * minutes. Returns 201 with the full chain — parameters, features and
     * prediction — so the workspace can render the result without a second
     * request.
     */
    @PostMapping("/api/deals/{dealId}/meeting-outputs")
    public ResponseEntity<MeetingOutputDetail> submitMeetingOutput(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable Long dealId,
            @Valid @RequestBody MeetingOutputRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(dealFlowService.submitMeetingOutput(caller, dealId, request));
    }

    @GetMapping("/api/deals/{dealId}/meeting-outputs")
    public List<MeetingOutputDetail> meetingOutputs(@AuthenticationPrincipal AuthenticatedUser caller,
                                                    @PathVariable Long dealId) {
        return dealFlowService.workspace(caller, dealId).meetings();
    }

    /** Deal flow step 10 — the sales manager's decision on the recommendation. */
    @PostMapping("/api/deals/{dealId}/review")
    public ResponseEntity<ManagerReviewResponse> review(@AuthenticationPrincipal AuthenticatedUser caller,
                                                        @PathVariable Long dealId,
                                                        @Valid @RequestBody ManagerReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dealFlowService.review(caller, dealId, request));
    }

    /* --------------------------------------------- onboarding (step 14) */

    public record OnboardingResponse(String id, String dealId, String opportunityId, String status,
                                     String notes, OffsetDateTime initiatedAt, OffsetDateTime completedAt) {
        static OnboardingResponse from(CustomerOnboarding o) {
            return o == null ? null
                    : new OnboardingResponse(String.valueOf(o.getId()), String.valueOf(o.getDealId()),
                    o.getOpportunityId(), o.getStatus(), o.getNotes(), o.getInitiatedAt(), o.getCompletedAt());
        }
    }

    public record OnboardingUpdateRequest(@NotBlank String status, String notes) {
    }

    /** Null body when the deal isn't Closed Won yet — the absence is the answer,
     *  not an error. */
    @GetMapping("/api/deals/{dealId}/onboarding")
    public OnboardingResponse onboarding(@AuthenticationPrincipal AuthenticatedUser caller,
                                         @PathVariable Long dealId) {
        return OnboardingResponse.from(onboardingService.findForDeal(caller, dealId));
    }

    @PatchMapping("/api/deals/{dealId}/onboarding")
    public OnboardingResponse updateOnboarding(@AuthenticationPrincipal AuthenticatedUser caller,
                                               @PathVariable Long dealId,
                                               @Valid @RequestBody OnboardingUpdateRequest request) {
        return OnboardingResponse.from(
                onboardingService.updateStatus(caller, dealId, request.status(), request.notes()));
    }

    /* --------------------------------------------------------- vocabulary */

    public record ParameterVocabulary(List<String> ordered,
                                      java.util.Map<String, String> displayNames,
                                      java.util.Map<String, List<String>> allowedValues,
                                      List<String> objectionTokens) {
    }

    /**
     * The extraction vocabulary, served from the same constants the backend
     * uses. The UI renders parameter names and value chips from this rather than
     * hardcoding its own copy, which would drift the first time the model bundle
     * was retrained with a new category.
     */
    @GetMapping("/api/deal-flow/parameters")
    public ParameterVocabulary parameters() {
        return new ParameterVocabulary(
                DealParameters.ORDERED,
                DealParameters.DISPLAY_NAMES,
                DealParameters.ALLOWED_VALUES,
                DealParameters.OBJECTION_TOKENS);
    }
}
