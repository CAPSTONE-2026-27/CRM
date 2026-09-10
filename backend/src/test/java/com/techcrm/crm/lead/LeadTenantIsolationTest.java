package com.techcrm.crm.lead;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.user.Role;
import com.techcrm.crm.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tenant boundary: one organization must never reach another's leads.
 *
 * This is the one bug class in the CRM that is a data breach rather than an
 * inconvenience, and until this file existed nothing asserted it held. The
 * guarantee is enforced in exactly one place -- {@code LeadService.getOrThrow},
 * which looks a lead up by id AND the caller's organizationId, then applies a
 * second narrowing for roles that may only see their own leads. Every read,
 * write and delete funnels through it.
 *
 * These tests pin that funnel rather than the database. A repository mock lets
 * them assert the thing that actually goes wrong in practice: someone reaching
 * for {@code findById(id)} because it is shorter than
 * {@code findByIdAndOrganizationId(id, orgId)}. With a mock, that change turns
 * a passing test red immediately -- the stub for the org-scoped lookup stops
 * being used, and the verification of its arguments fails.
 *
 * What they deliberately do NOT prove: that the SQL is right, or that the
 * database enforces anything on its own. Both would need a real Postgres, and
 * the only instance available here is shared with the team -- creating and
 * deleting tenants inside it during a test run is not a trade worth making. A
 * Testcontainers suite is the right follow-up; this is the part worth having
 * before that exists.
 */
class LeadTenantIsolationTest {

    private static final long ORG_A = 1L;
    private static final long ORG_B = 2L;

    private static final long ADMIN_A = 10L;
    private static final long REP_A = 11L;
    private static final long COLLEAGUE_A = 12L;

    /** A lead that exists, but in the other tenant. */
    private static final long FOREIGN_LEAD = 500L;
    /** An id that exists nowhere at all. */
    private static final long ABSENT_LEAD = 999L;

    private LeadRepository leads;
    private UserRepository users;
    private AiScoringClient ai;
    private LeadService service;

    @BeforeEach
    void setUp() {
        leads = mock(LeadRepository.class);
        users = mock(UserRepository.class);
        ai = mock(AiScoringClient.class);
        service = new LeadService(leads, users, ai);
    }

    // ---- fixtures ---------------------------------------------------------

    private static AuthenticatedUser admin(long organizationId, long userId) {
        return new AuthenticatedUser(userId, organizationId, Role.ADMIN, List.of(), false);
    }

    private static AuthenticatedUser rep(long organizationId, long userId) {
        return new AuthenticatedUser(userId, organizationId, Role.SALES_REP, List.of(), false);
    }

    private static Lead lead(long id, long organizationId, Long assignedToId) {
        Lead l = new Lead();
        l.setId(id);
        l.setOrganizationId(organizationId);
        l.setAssignedToId(assignedToId);
        l.setFullName("Test Lead " + id);
        l.setCompany("Acme");
        l.setStatus("NEW");
        l.setQualificationStatus("PENDING");
        l.setContactStatus("NOT_CONTACTED");
        l.setAssignmentStatus(assignedToId == null ? "UNASSIGNED" : "ASSIGNED");
        return l;
    }

    /**
     * The org-scoped lookup finds nothing, which is what the real query does
     * for a lead belonging to a different tenant: the row exists, but not for
     * this caller.
     */
    private void foreignLeadIsInvisible() {
        when(leads.findByIdAndOrganizationId(FOREIGN_LEAD, ORG_A)).thenReturn(Optional.empty());
    }

    private static void assertNotFound(ThrowableAssertCallable call) {
        assertThatThrownBy(call::call)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @FunctionalInterface
    private interface ThrowableAssertCallable {
        void call() throws Exception;
    }

    // ---- cross-tenant -----------------------------------------------------

    @Nested
    @DisplayName("a lead in another organization")
    class AnotherOrganisation {

        @Test
        @DisplayName("cannot be read, and is looked up under the caller's org id")
        void cannotBeRead() {
            foreignLeadIsInvisible();
            AuthenticatedUser caller = admin(ORG_A, ADMIN_A);

            assertNotFound(() -> service.findById(caller, FOREIGN_LEAD));

            // The assertion that matters: the org id came from the CALLER, never
            // from the requested lead. Swapping in findById(id) breaks this.
            verify(leads).findByIdAndOrganizationId(FOREIGN_LEAD, ORG_A);
        }

        @Test
        @DisplayName("cannot be deleted, and nothing is deleted in the attempt")
        void cannotBeDeleted() {
            foreignLeadIsInvisible();
            AuthenticatedUser caller = admin(ORG_A, ADMIN_A);

            assertNotFound(() -> service.delete(caller, FOREIGN_LEAD));

            verify(leads).findByIdAndOrganizationId(FOREIGN_LEAD, ORG_A);
            verify(leads, never()).delete(any(Lead.class));
            verify(leads, never()).deleteById(anyLong());
        }

        @Test
        @DisplayName("cannot have its contact status changed, and is never saved")
        void cannotBeUpdated() {
            foreignLeadIsInvisible();
            AuthenticatedUser caller = admin(ORG_A, ADMIN_A);

            assertNotFound(() -> service.updateContactStatus(caller, FOREIGN_LEAD, "INTERESTED", "hello"));

            verify(leads, never()).save(any());
        }

        @Test
        @DisplayName("cannot be assigned to a user in the caller's org")
        void cannotBeAssigned() {
            foreignLeadIsInvisible();
            AuthenticatedUser caller = admin(ORG_A, ADMIN_A);

            assertNotFound(() -> service.assign(caller, FOREIGN_LEAD, String.valueOf(REP_A)));

            verify(leads, never()).save(any());
        }

        @Test
        @DisplayName("is skipped by a bulk delete rather than deleted with the rest")
        void isSkippedByBulkDelete() {
            Lead own = lead(1L, ORG_A, null);
            when(leads.findByIdAndOrganizationId(1L, ORG_A)).thenReturn(Optional.of(own));
            foreignLeadIsInvisible();

            int deleted = service.bulkDelete(admin(ORG_A, ADMIN_A), List.of(1L, FOREIGN_LEAD));

            // The caller's own lead goes; the other tenant's survives untouched.
            // Exactly one delete in total is the strongest form of that claim.
            assertThat(deleted).isEqualTo(1);
            verify(leads).delete(own);
            verify(leads, times(1)).delete(any(Lead.class));
        }

        @Test
        @DisplayName("is indistinguishable from a lead that does not exist")
        void leaksNothingThroughTheError() {
            foreignLeadIsInvisible();
            when(leads.findByIdAndOrganizationId(ABSENT_LEAD, ORG_A)).thenReturn(Optional.empty());
            AuthenticatedUser caller = admin(ORG_A, ADMIN_A);

            ResponseStatusException foreign = catchNotFound(() -> service.findById(caller, FOREIGN_LEAD));
            ResponseStatusException absent = catchNotFound(() -> service.findById(caller, ABSENT_LEAD));

            // Same status and same shape of message. A 403 here, or a message
            // that said "belongs to another organization", would confirm the
            // lead exists -- which is itself a leak.
            assertThat(foreign.getStatusCode()).isEqualTo(absent.getStatusCode());
            assertThat(foreign.getReason()).isNotNull();
            assertThat(foreign.getReason().replace(String.valueOf(FOREIGN_LEAD), "#"))
                    .isEqualTo(absent.getReason().replace(String.valueOf(ABSENT_LEAD), "#"));
        }
    }

    // ---- within one tenant ------------------------------------------------

    @Nested
    @DisplayName("inside one organization, a sales rep")
    class ScopedRole {

        @Test
        @DisplayName("can read a lead assigned to them")
        void seesOwnLead() {
            Lead own = lead(1L, ORG_A, REP_A);
            when(leads.findByIdAndOrganizationId(1L, ORG_A)).thenReturn(Optional.of(own));

            LeadResponse response = service.findById(rep(ORG_A, REP_A), 1L);

            assertThat(response.id()).isEqualTo("1");
        }

        @Test
        @DisplayName("gets 404 -- not 403 -- for a colleague's lead in the same org")
        void cannotSeeAColleaguesLead() {
            Lead colleagues = lead(2L, ORG_A, COLLEAGUE_A);
            when(leads.findByIdAndOrganizationId(2L, ORG_A)).thenReturn(Optional.of(colleagues));

            // 403 would confirm the lead exists and simply isn't theirs. 404 is
            // the deliberate choice in getOrThrow, and this pins it.
            assertNotFound(() -> service.findById(rep(ORG_A, REP_A), 2L));
        }

        @Test
        @DisplayName("cannot delete a colleague's lead")
        void cannotDeleteAColleaguesLead() {
            Lead colleagues = lead(2L, ORG_A, COLLEAGUE_A);
            when(leads.findByIdAndOrganizationId(2L, ORG_A)).thenReturn(Optional.of(colleagues));

            assertNotFound(() -> service.delete(rep(ORG_A, REP_A), 2L));

            verify(leads, never()).delete(any(Lead.class));
        }

        @Test
        @DisplayName("lists only their own leads, scoped by org as well as by owner")
        void listsOnlyOwnLeads() {
            when(leads.findByOrganizationIdAndAssignedToId(ORG_A, REP_A)).thenReturn(List.of(lead(1L, ORG_A, REP_A)));

            service.findAll(rep(ORG_A, REP_A));

            verify(leads).findByOrganizationIdAndAssignedToId(ORG_A, REP_A);
            verify(leads, never()).findByOrganizationId(anyLong());
        }
    }

    @Nested
    @DisplayName("inside one organization, an admin")
    class UnscopedRole {

        @Test
        @DisplayName("can read any lead in their own organization")
        void seesAnyLeadInOrg() {
            Lead someoneElses = lead(2L, ORG_A, COLLEAGUE_A);
            when(leads.findByIdAndOrganizationId(2L, ORG_A)).thenReturn(Optional.of(someoneElses));

            LeadResponse response = service.findById(admin(ORG_A, ADMIN_A), 2L);

            assertThat(response.id()).isEqualTo("2");
        }

        @Test
        @DisplayName("still lists within their own organization only")
        void listsWithinOrgOnly() {
            when(leads.findByOrganizationId(ORG_A)).thenReturn(List.of(lead(1L, ORG_A, null)));

            service.findAll(admin(ORG_A, ADMIN_A));

            verify(leads).findByOrganizationId(ORG_A);
            // Never the other tenant's id, whatever else happens.
            verify(leads, never()).findByOrganizationId(ORG_B);
        }
    }

    private static ResponseStatusException catchNotFound(ThrowableAssertCallable call) {
        try {
            call.call();
        } catch (ResponseStatusException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError("expected ResponseStatusException, got " + e, e);
        }
        throw new AssertionError("expected a ResponseStatusException, but nothing was thrown");
    }
}
