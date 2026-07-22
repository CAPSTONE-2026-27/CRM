package com.techcrm.crm.analytics;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.lead.LeadMapper;
import com.techcrm.crm.lead.LeadRepository;
import com.techcrm.crm.user.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
public class AnalyticsService {

    private final LeadRepository leadRepository;

    public AnalyticsService(LeadRepository leadRepository) {
        this.leadRepository = leadRepository;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryResponse dashboard(AuthenticatedUser caller) {
        // Same visibility rule as LeadService: ADMIN/MANAGER see the whole
        // org, SALES_REP/SUPPORT_AGENT see only what's assigned to them —
        // so "Total leads" on the dashboard means "leads assigned to me"
        // for a rep, and "leads in my org" for an admin.
        boolean scopedToOwnLeads = caller.role() == Role.SALES_REP || caller.role() == Role.SUPPORT_AGENT;

        List<Lead> visible = scopedToOwnLeads
                ? leadRepository.findByOrganizationIdAndAssignedToId(caller.organizationId(), caller.userId())
                : leadRepository.findByOrganizationId(caller.organizationId());

        List<com.techcrm.crm.lead.LeadResponse> recentLeads = visible.stream()
                .sorted(Comparator.comparing(Lead::getCreatedAt).reversed())
                .limit(5)
                .map(LeadMapper::toResponse)
                .toList();

        DashboardMetrics metrics = new DashboardMetrics(visible.size(), 0, 0, 0, 0);

        return new DashboardSummaryResponse(metrics, recentLeads, List.of(), List.of());
    }
}
