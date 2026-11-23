package com.techcrm.crm.analytics;

import com.techcrm.crm.analytics.AnalyticsAccess.Section;
import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which roles see which analytics sections, and how a refusal is reported. */
class AnalyticsAccessTest {

    private static AuthenticatedUser user(Role role, String... permissions) {
        return new AuthenticatedUser(7L, 1L, role, List.of(permissions), false);
    }

    @Test
    void sectionsFollowTheRole() {
        assertThat(AnalyticsAccess.sectionsFor(user(Role.SALES_REP, "analytics")))
                .containsExactly("OVERVIEW", "LEADS", "DEALS", "AI");
        assertThat(AnalyticsAccess.sectionsFor(user(Role.SUPPORT_AGENT, "analytics")))
                .containsExactly("OVERVIEW", "SUPPORT");
        assertThat(AnalyticsAccess.sectionsFor(user(Role.MARKETING, "analytics")))
                .containsExactly("OVERVIEW", "LEADS", "CAMPAIGNS");
        assertThat(AnalyticsAccess.sectionsFor(user(Role.MANAGER, "analytics"))).hasSize(Section.values().length);
    }

    @Test
    void aSectionOutsideTheRoleIsNotFoundRatherThanForbidden() {
        assertThatThrownBy(() -> AnalyticsAccess.requireSection(user(Role.SALES_REP, "analytics"), Section.TEAM))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void theModuleNeedsTheAnalyticsPermissionExceptForAdmins() {
        assertThatThrownBy(() -> AnalyticsAccess.requireModule(user(Role.MANAGER, "leads")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        AnalyticsAccess.requireModule(user(Role.ADMIN));
    }

    @Test
    void onlySalesRepsAreScopedToTheirOwnBook() {
        assertThat(AnalyticsAccess.isScopedToSelf(user(Role.SALES_REP))).isTrue();
        assertThat(AnalyticsAccess.isScopedToSelf(user(Role.MANAGER))).isFalse();
        assertThat(AnalyticsAccess.isScopedToSelf(user(Role.MARKETING))).isFalse();
    }
}
