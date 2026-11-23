package com.techcrm.crm.deal;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.deal.DealDtos.DealRequest;
import com.techcrm.crm.deal.DealDtos.DealResponse;
import com.techcrm.crm.deal.DealDtos.DealStageRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/deals")
public class DealController {

    private final DealService dealService;

    public DealController(DealService dealService) {
        this.dealService = dealService;
    }

    @GetMapping
    public List<DealResponse> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return dealService.list(caller);
    }

    @GetMapping("/{id}")
    public DealResponse get(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        return dealService.get(caller, id);
    }

    @PostMapping
    public ResponseEntity<DealResponse> create(@AuthenticationPrincipal AuthenticatedUser caller,
                                               @Valid @RequestBody DealRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dealService.create(caller, request));
    }

    @PutMapping("/{id}")
    public DealResponse update(@AuthenticationPrincipal AuthenticatedUser caller,
                               @PathVariable Long id,
                               @Valid @RequestBody DealRequest request) {
        return dealService.update(caller, id, request);
    }

    /** Used by the pipeline board's drag-and-drop. */
    @PatchMapping("/{id}")
    public DealResponse updateStage(@AuthenticationPrincipal AuthenticatedUser caller,
                                    @PathVariable Long id,
                                    @Valid @RequestBody DealStageRequest request) {
        return dealService.updateStage(caller, id, request.stage(), request.closingReason());
    }

    /** Sets who this deal's proposals and contracts go to. <b>400</b> when the
     *  contact is unknown or belongs to another account. */
    @PutMapping("/{id}/primary-contact")
    public DealResponse primaryContact(@AuthenticationPrincipal AuthenticatedUser caller,
                                       @PathVariable Long id,
                                       @RequestBody DealDtos.PrimaryContactRequest request) {
        return dealService.setPrimaryContact(caller, id, request.contactId());
    }

    /** Deal flow step 2 — schedule the customer meeting. */
    /**
     * Records the terms agreed during negotiation, so the contract carries them.
     *
     * <b>200</b> the updated deal. <b>409</b> the deal is not in Negotiation,
     * <b>404</b> no such deal in this organization, <b>400</b> a non-positive
     * amount or an over-long term.
     */
    @PostMapping("/{id}/negotiated-terms")
    public DealResponse negotiatedTerms(@AuthenticationPrincipal AuthenticatedUser caller,
                                        @PathVariable Long id,
                                        @Valid @RequestBody DealDtos.NegotiatedTermsRequest request) {
        return dealService.recordNegotiatedTerms(caller, id, request);
    }

    @PostMapping("/{id}/schedule-meeting")
    public DealResponse scheduleMeeting(@AuthenticationPrincipal AuthenticatedUser caller,
                                        @PathVariable Long id,
                                        @Valid @RequestBody DealDtos.ScheduleMeetingRequest request) {
        return dealService.scheduleMeeting(caller, id, request.meetingScheduledAt(),
                request.meetingMode(), request.meetingParticipants());
    }

    /** Deal flow step 13 — record the customer's final decision with its reason. */
    @PostMapping("/{id}/close")
    public DealResponse close(@AuthenticationPrincipal AuthenticatedUser caller,
                              @PathVariable Long id,
                              @Valid @RequestBody DealDtos.CloseDealRequest request) {
        return dealService.updateStage(caller, id, request.stage(), request.closingReason());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        dealService.delete(caller, id);
        return ResponseEntity.noContent().build();
    }
}
