package com.techcrm.crm.campaign;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.campaign.CampaignDtos.CampaignRequest;
import com.techcrm.crm.campaign.CampaignDtos.CampaignResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    private final CampaignService campaignService;

    public CampaignController(CampaignService campaignService) {
        this.campaignService = campaignService;
    }

    @GetMapping
    public List<CampaignResponse> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return campaignService.list(caller);
    }

    @GetMapping("/{id}")
    public CampaignResponse get(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        return campaignService.get(caller, id);
    }

    @PostMapping
    public ResponseEntity<CampaignResponse> create(@AuthenticationPrincipal AuthenticatedUser caller,
                                                   @Valid @RequestBody CampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(campaignService.create(caller, request));
    }

    @PutMapping("/{id}")
    public CampaignResponse update(@AuthenticationPrincipal AuthenticatedUser caller,
                                   @PathVariable Long id,
                                   @Valid @RequestBody CampaignRequest request) {
        return campaignService.update(caller, id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        campaignService.delete(caller, id);
        return ResponseEntity.noContent().build();
    }
}
