package com.techcrm.crm.deal;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.user.Role;

import java.util.List;
import java.util.Optional;

/**
 * Who may see which deals — the one place the pipeline's visibility rule lives.
 *
 * Mirrors LeadService.isScopedToOwnLeads: ADMIN and MANAGER see the whole
 * organization's pipeline, SALES_REP and SUPPORT_AGENT see only the deals they
 * own. Every deal read in DealService, DealFlowService and the deal coach's
 * context goes through here, so the pipeline board, the deal workspace and the
 * coach can never disagree about what a rep is allowed to see.
 *
 * A deal outside the caller's scope is reported as absent, not forbidden —
 * 404 rather than 403, so a rep cannot probe for the existence of a
 * colleague's deals by id.
 */
public final class DealAccess {

    private DealAccess() {
    }

    public static boolean isScopedToOwnDeals(AuthenticatedUser caller) {
        return caller.role() == Role.SALES_REP || caller.role() == Role.SUPPORT_AGENT;
    }

    public static List<Deal> visibleDeals(DealRepository repository, AuthenticatedUser caller) {
        return isScopedToOwnDeals(caller)
                ? repository.findByOrganizationIdAndOwnerIdOrderByCreatedAtDesc(caller.organizationId(), caller.userId())
                : repository.findByOrganizationIdOrderByCreatedAtDesc(caller.organizationId());
    }

    public static Optional<Deal> findVisible(DealRepository repository, AuthenticatedUser caller, Long id) {
        return isScopedToOwnDeals(caller)
                ? repository.findByIdAndOrganizationIdAndOwnerId(id, caller.organizationId(), caller.userId())
                : repository.findByIdAndOrganizationId(id, caller.organizationId());
    }
}
