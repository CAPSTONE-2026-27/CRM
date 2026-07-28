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
        return dealService.updateStage(caller, id, request.stage());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        dealService.delete(caller, id);
        return ResponseEntity.noContent().build();
    }
}
