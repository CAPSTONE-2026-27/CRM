package com.techcrm.crm.campaign;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.campaign.CampaignDtos.CampaignRequest;
import com.techcrm.crm.campaign.CampaignDtos.CampaignResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@Service
public class CampaignService {

    private static final Set<String> CHANNELS = Set.of("EMAIL", "SMS_EMAIL", "MULTI_CHANNEL");
    private static final Set<String> STATUSES = Set.of("DRAFT", "SCHEDULED", "ACTIVE", "COMPLETED");

    private final CampaignRepository campaignRepository;

    public CampaignService(CampaignRepository campaignRepository) {
        this.campaignRepository = campaignRepository;
    }

    @Transactional(readOnly = true)
    public List<CampaignResponse> list(AuthenticatedUser caller) {
        return campaignRepository.findByOrganizationIdOrderByCreatedAtDesc(caller.organizationId())
                .stream().map(CampaignResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public CampaignResponse get(AuthenticatedUser caller, Long id) {
        return CampaignResponse.from(require(caller, id));
    }

    @Transactional
    public CampaignResponse create(AuthenticatedUser caller, CampaignRequest request) {
        Campaign campaign = new Campaign();
        campaign.setOrganizationId(caller.organizationId());
        apply(campaign, request);
        return CampaignResponse.from(campaignRepository.save(campaign));
    }

    @Transactional
    public CampaignResponse update(AuthenticatedUser caller, Long id, CampaignRequest request) {
        Campaign campaign = require(caller, id);
        apply(campaign, request);
        return CampaignResponse.from(campaignRepository.save(campaign));
    }

    @Transactional
    public void delete(AuthenticatedUser caller, Long id) {
        campaignRepository.delete(require(caller, id));
    }

    private Campaign require(AuthenticatedUser caller, Long id) {
        return campaignRepository.findByIdAndOrganizationId(id, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
    }

    private void apply(Campaign campaign, CampaignRequest request) {
        campaign.setName(request.name());
        campaign.setGoal(request.goal());
        campaign.setBudget(request.budget());
        campaign.setOwnerId(request.ownerId());
        campaign.setStartDate(request.startDate());
        campaign.setEndDate(request.endDate());
        campaign.setSegment(request.segment());
        campaign.setRegion(request.region());
        campaign.setEstimatedReach(request.estimatedReach());
        campaign.setOpenRatePct(request.openRatePct());
        if (request.sentCount() != null) campaign.setSentCount(request.sentCount());
        if (request.channel() != null) {
            campaign.setChannel(validate(request.channel(), CHANNELS, "campaign channel"));
        }
        if (request.status() != null) {
            campaign.setStatus(validate(request.status(), STATUSES, "campaign status"));
        }
    }

    private String validate(String value, Set<String> allowed, String label) {
        String normalised = value.trim().toUpperCase();
        if (!allowed.contains(normalised)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown " + label + ": " + value);
        }
        return normalised;
    }
}
