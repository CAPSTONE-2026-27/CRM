package com.techcrm.crm.deal;

import com.techcrm.crm.account.Account;
import com.techcrm.crm.account.AccountRepository;
import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.deal.DealDtos.DealRequest;
import com.techcrm.crm.onboarding.CustomerOnboardingService;
import com.techcrm.crm.user.Role;
import com.techcrm.crm.user.User;
import com.techcrm.crm.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The pipeline follows the sales rep: a rep's board, workspace and coach show
 * only the deals they own, while ADMIN/MANAGER see the whole organization.
 * Same shape as LeadTenantIsolationTest — pins the repository funnel so a
 * shortcut to the org-wide query turns a test red.
 */
class DealOwnershipScopeTest {

    private static final long ORG = 1L;
    private static final long REP = 11L;
    private static final long COLLEAGUE = 12L;
    private static final long MANAGER = 10L;
    private static final long ACCOUNT = 100L;

    private DealRepository deals;
    private UserRepository users;
    private DealService service;

    @BeforeEach
    void setUp() {
        deals = mock(DealRepository.class);
        users = mock(UserRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        DealScoringClient scoring = mock(DealScoringClient.class);
        service = new DealService(deals, accounts, scoring, mock(CustomerOnboardingService.class), users,
                mock(com.techcrm.crm.contact.ContactRepository.class),
                mock(org.springframework.context.ApplicationEventPublisher.class));

        when(accounts.findByIdAndOrganizationId(ACCOUNT, ORG)).thenReturn(Optional.of(new Account()));
        when(deals.save(any(Deal.class))).thenAnswer(inv -> {
            Deal d = inv.getArgument(0);
            if (d.getId() == null) {
                d.setId(42L);
            }
            return d;
        });
    }

    private static AuthenticatedUser as(long userId, Role role) {
        return new AuthenticatedUser(userId, ORG, role, List.of(), false);
    }

    private static Deal owned(long id, Long ownerId) {
        Deal d = new Deal();
        d.setId(id);
        d.setOrganizationId(ORG);
        d.setOwnerId(ownerId);
        d.setName("Deal " + id);
        d.setAccountId(ACCOUNT);
        d.setValue(BigDecimal.TEN);
        return d;
    }

    private static DealRequest request(Long ownerId) {
        return new DealRequest("Acme ERP", ACCOUNT, BigDecimal.valueOf(1_000_000), "INR", null, "PROSPECTING",
                null, ownerId, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    @Test
    void repListsOnlyTheirOwnDeals() {
        when(deals.findByOrganizationIdAndOwnerIdOrderByCreatedAtDesc(ORG, REP)).thenReturn(List.of(owned(1, REP)));

        assertThat(service.list(as(REP, Role.SALES_REP))).hasSize(1);
        verify(deals, never()).findByOrganizationIdOrderByCreatedAtDesc(anyLong());
    }

    @Test
    void managerListsTheWholeOrganization() {
        when(deals.findByOrganizationIdOrderByCreatedAtDesc(ORG))
                .thenReturn(List.of(owned(1, REP), owned(2, COLLEAGUE)));

        assertThat(service.list(as(MANAGER, Role.MANAGER))).hasSize(2);
    }

    @Test
    void repCannotOpenAColleaguesDeal() {
        when(deals.findByIdAndOrganizationIdAndOwnerId(2L, ORG, REP)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(as(REP, Role.SALES_REP), 2L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(deals, never()).findByIdAndOrganizationId(anyLong(), anyLong());
    }

    @Test
    void repCreatingADealOwnsItEvenIfTheyNameSomeoneElse() {
        service.create(as(REP, Role.SALES_REP), request(COLLEAGUE));

        verify(deals, atLeastOnce()).save(argThat(d -> d.getOwnerId() == REP));
    }

    @Test
    void dealWithoutAnOwnerInTheRequestDefaultsToItsCreator() {
        service.create(as(MANAGER, Role.MANAGER), request(null));

        verify(deals, atLeastOnce()).save(argThat(d -> d.getOwnerId() == MANAGER));
    }

    @Test
    void editingWithoutAnOwnerKeepsTheExistingOne() {
        // The deal form never sends ownerId; before this, every edit unassigned
        // the deal and dropped it off its rep's board.
        Deal existing = owned(5, COLLEAGUE);
        when(deals.findByIdAndOrganizationId(5L, ORG)).thenReturn(Optional.of(existing));

        service.update(as(MANAGER, Role.MANAGER), 5L, request(null));

        assertThat(existing.getOwnerId()).isEqualTo(COLLEAGUE);
    }

    @Test
    void managerCanReassignOnlyWithinTheOrganization() {
        Deal existing = owned(5, REP);
        when(deals.findByIdAndOrganizationId(5L, ORG)).thenReturn(Optional.of(existing));
        when(users.findByIdAndOrganizationIdAndDeletedAtIsNull(COLLEAGUE, ORG)).thenReturn(Optional.of(new User()));
        when(users.findByIdAndOrganizationIdAndDeletedAtIsNull(999L, ORG)).thenReturn(Optional.empty());

        service.update(as(MANAGER, Role.MANAGER), 5L, request(COLLEAGUE));
        assertThat(existing.getOwnerId()).isEqualTo(COLLEAGUE);

        assertThatThrownBy(() -> service.update(as(MANAGER, Role.MANAGER), 5L, request(999L)))
                .isInstanceOf(ResponseStatusException.class);
    }
}
