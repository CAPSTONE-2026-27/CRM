package com.techcrm.crm.marketing.common;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.user.Role;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-side authorization for the module. The sidebar hides the screen from
 * users without the permission, but that is UX only; these checks are what
 * actually protect campaign data.
 *
 * Two levels:
 * <ul>
 *   <li><b>marketing</b>: view campaigns, create and edit them, run the AI
 *       pipelines, edit content and save drafts.</li>
 *   <li><b>Approver</b> (the Marketing Head): everything above, plus every
 *       decision that lets AI output reach a customer: approving or rejecting
 *       leads and content, overriding the AI, choosing an A/B winner,
 *       scheduling, launching, pausing, resuming, cancelling and deleting.
 *       Held by ADMIN and MANAGER, and by any user granted
 *       {@value #APPROVE_PERMISSION}.</li>
 * </ul>
 */
public final class MarketingAccess {

    public static final String VIEW_PERMISSION = "marketing";
    public static final String APPROVE_PERMISSION = "marketing_approve";

    private MarketingAccess() {
    }

    public static boolean canView(AuthenticatedUser caller) {
        if (caller == null) return false;
        return caller.role() == Role.ADMIN || has(caller, VIEW_PERMISSION);
    }

    public static boolean isApprover(AuthenticatedUser caller) {
        if (!canView(caller)) return false;
        return caller.role() == Role.ADMIN
                || caller.role() == Role.MANAGER
                || has(caller, APPROVE_PERMISSION);
    }

    public static void requireView(AuthenticatedUser caller) {
        if (!canView(caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You don't have access to Marketing Automation.");
        }
    }

    public static void requireApprover(AuthenticatedUser caller) {
        requireView(caller);
        if (!isApprover(caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a Marketing Head (marketing approver) can make this decision.");
        }
    }

    private static boolean has(AuthenticatedUser caller, String permission) {
        return caller.permissions() != null && caller.permissions().contains(permission);
    }
}
