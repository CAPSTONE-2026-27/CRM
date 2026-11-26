package com.techcrm.crm.marketing.approval;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.marketing.api.MarketingDtos.ApprovalItem;
import com.techcrm.crm.marketing.campaign.MarketingCampaign;
import com.techcrm.crm.marketing.campaign.MarketingCampaignRepository;
import com.techcrm.crm.marketing.common.UserNames;
import com.techcrm.crm.marketing.leads.CampaignLeadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The organization-wide approval queue, oldest first so nothing waits unseen. */
@Service
public class ApprovalQueryService {

    private final MarketingApprovalRepository approvals;
    private final MarketingCampaignRepository campaigns;
    private final CampaignLeadRepository campaignLeads;
    private final UserNames userNames;

    public ApprovalQueryService(MarketingApprovalRepository approvals, MarketingCampaignRepository campaigns,
                                CampaignLeadRepository campaignLeads, UserNames userNames) {
        this.approvals = approvals;
        this.campaigns = campaigns;
        this.campaignLeads = campaignLeads;
        this.userNames = userNames;
    }

    @Transactional(readOnly = true)
    public List<ApprovalItem> queue(AuthenticatedUser caller) {
        List<MarketingApproval> pending = approvals.findByOrganizationIdAndStatusOrderBySubmittedAtAsc(
                caller.organizationId(), MarketingApproval.Status.PENDING);
        if (pending.isEmpty()) return List.of();

        Set<Long> campaignIds = pending.stream().map(MarketingApproval::getCampaignId).collect(Collectors.toSet());
        Map<Long, MarketingCampaign> byId = campaigns.findAllById(campaignIds).stream()
                .filter(c -> Objects.equals(c.getOrganizationId(), caller.organizationId()))
                .collect(Collectors.toMap(MarketingCampaign::getId, Function.identity()));
        Map<Long, Long> leadsWaiting = new HashMap<>();
        for (Object[] row : campaignLeads.countsByCampaign(campaignIds)) {
            leadsWaiting.put((Long) row[0], row[3] == null ? 0 : ((Number) row[3]).longValue());
        }
        Map<Long, String> names = userNames.resolve(caller.organizationId(),
                pending.stream().map(MarketingApproval::getSubmittedBy).toList());

        return pending.stream().filter(a -> byId.containsKey(a.getCampaignId())).map(a -> {
            MarketingCampaign c = byId.get(a.getCampaignId());
            return new ApprovalItem(String.valueOf(a.getId()), String.valueOf(c.getId()), c.getName(),
                    c.getCampaignType().name(), a.getKind().name(), a.getStatus().name(),
                    a.getSubmittedBy() == null ? null : names.getOrDefault(a.getSubmittedBy(), "User " + a.getSubmittedBy()),
                    a.getSubmittedAt(), null, null, a.getNote(),
                    a.getKind() == MarketingApproval.Kind.LEADS ? leadsWaiting.getOrDefault(c.getId(), 0L) : 0);
        }).toList();
    }
}
