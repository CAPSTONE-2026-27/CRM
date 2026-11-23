package com.techcrm.crm.analytics;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.user.Role;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who may see which analytics — the one place the reporting visibility rule
 * lives.
 *
 * Two layers. The "analytics" screen permission gates the module as a whole
 * (ADMIN always has it), exactly as the sidebar does. Within it, each section
 * follows the role: sales reps see the sales sections for their own book,
 * support agents see support, marketing sees leads and campaigns, and
 * managers/admins see everything for the whole organization.
 *
 * A section outside the caller's role is reported as absent (404), not
 * forbidden — the same rule DealAccess applies to out-of-scope records.
 */
public final class AnalyticsAccess {

    public static final String PERMISSION = "analytics";

    public enum Section { OVERVIEW, LEADS, DEALS, TEAM, AI, SUPPORT, CAMPAIGNS }

    private static final Map<Role, Set<Section>> SECTIONS = Map.of(
            Role.ADMIN, EnumSet.allOf(Section.class),
            Role.MANAGER, EnumSet.allOf(Section.class),
            Role.SALES_REP, EnumSet.of(Section.OVERVIEW, Section.LEADS, Section.DEALS, Section.AI),
            Role.SUPPORT_AGENT, EnumSet.of(Section.OVERVIEW, Section.SUPPORT),
            Role.MARKETING, EnumSet.of(Section.OVERVIEW, Section.LEADS, Section.CAMPAIGNS)
    );

    private AnalyticsAccess() {
    }

    public static void requireModule(AuthenticatedUser caller) {
        boolean allowed = caller.role() == Role.ADMIN
                || (caller.permissions() != null && caller.permissions().contains(PERMISSION));
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You don't have access to Analytics.");
        }
    }

    public static void requireSection(AuthenticatedUser caller, Section section) {
        requireModule(caller);
        if (!canSee(caller, section)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
    }

    public static boolean canSee(AuthenticatedUser caller, Section section) {
        return SECTIONS.getOrDefault(caller.role(), Set.of()).contains(section);
    }

    public static List<String> sectionsFor(AuthenticatedUser caller) {
        return Arrays.stream(Section.values()).filter(s -> canSee(caller, s)).map(Enum::name).toList();
    }

    /** Sales data follows its owner for a rep; every other role that can see
     *  a sales section sees it org-wide. */
    public static boolean isScopedToSelf(AuthenticatedUser caller) {
        return caller.role() == Role.SALES_REP;
    }
}
