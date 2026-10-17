package com.techcrm.crm.deal;

import com.techcrm.crm.account.AccountRepository;
import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.contact.ContactRepository;
import com.techcrm.crm.deal.DealDtos.NegotiatedTermsRequest;
import com.techcrm.crm.onboarding.CustomerOnboardingService;
import com.techcrm.crm.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a sales representative may record during a negotiation, and when.
 *
 * The rules under test exist because a proposal is a snapshot of what was
 * offered and a contract is a snapshot of what was agreed. Terms recorded
 * before the customer has the offer are premature; terms recorded after the
 * deal has left for the contract stage would disagree with a document that may
 * already be with them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("negotiated terms")
class DealNegotiatedTermsTest {

    private static final Long ORG_ID = 8L;
    private static final Long DEAL_ID = 31L;

    @Mock DealRepository dealRepository;
    @Mock AccountRepository accountRepository;
    @Mock DealScoringClient dealScoringClient;
    @Mock CustomerOnboardingService onboardingService;
    @Mock ContactRepository contactRepository;
    @Mock org.springframework.context.ApplicationEventPublisher events;

    DealService service;

    private static AuthenticatedUser caller() {
        return new AuthenticatedUser(1L, ORG_ID, Role.SALES_REP, List.of(), false);
    }

    private static Deal dealAt(String stage) {
        Deal deal = new Deal();
        deal.setId(DEAL_ID);
        deal.setOrganizationId(ORG_ID);
        deal.setName("UrbanMart Digital Commerce Transformation");
        deal.setAccountId(12L);
        deal.setValue(new BigDecimal("1650000.00"));
        deal.setCurrency("INR");
        deal.setStage(stage);
        return deal;
    }

    @BeforeEach
    void setUp() {
        service = new DealService(dealRepository, accountRepository, dealScoringClient, onboardingService,
                contactRepository, events);
        when(dealRepository.save(any(Deal.class))).thenAnswer(i -> i.getArgument(0));
    }

    private void dealIs(String stage) {
        when(dealRepository.findByIdAndOrganizationId(DEAL_ID, ORG_ID))
                .thenReturn(Optional.of(dealAt(stage)));
    }

    @Test
    void recordsTheAgreedAmountAndTermsOnADealInNegotiation() {
        dealIs(DealStages.NEGOTIATION);

        var response = service.recordNegotiatedTerms(caller(), DEAL_ID,
                new NegotiatedTermsRequest(new BigDecimal("1450000.00"),
                        "40% on signature, 60% on delivery.",
                        "Within 45 days of the contract start date."));

        assertThat(response.value()).isEqualByComparingTo("1450000.00");
        assertThat(response.paymentTerms()).isEqualTo("40% on signature, 60% on delivery.");
        assertThat(response.deliveryTimeline()).isEqualTo("Within 45 days of the contract start date.");
        assertThat(response.termsAgreedAt()).isNotNull();
    }

    /** The agreed price is the deal's value, because that is what the contract's
     *  line items are derived from. */
    @Test
    void theAgreedAmountReplacesTheDealValue() {
        dealIs(DealStages.NEGOTIATION);

        var response = service.recordNegotiatedTerms(caller(), DEAL_ID,
                new NegotiatedTermsRequest(new BigDecimal("900000.00"), null, null));

        assertThat(response.value()).isEqualByComparingTo("900000.00");
    }

    /** A negotiation that moved only the price sends only the price; the other
     *  fields must not be wiped by their absence. */
    @Test
    void anOmittedFieldLeavesTheExistingValueAlone() {
        Deal deal = dealAt(DealStages.NEGOTIATION);
        deal.setPaymentTerms("Existing terms");
        deal.setDeliveryTimeline("Existing schedule");
        when(dealRepository.findByIdAndOrganizationId(DEAL_ID, ORG_ID)).thenReturn(Optional.of(deal));

        var response = service.recordNegotiatedTerms(caller(), DEAL_ID,
                new NegotiatedTermsRequest(new BigDecimal("500000.00"), null, null));

        assertThat(response.paymentTerms()).isEqualTo("Existing terms");
        assertThat(response.deliveryTimeline()).isEqualTo("Existing schedule");
    }

    @Test
    void aBlankTermIsTreatedAsAbsentRatherThanAsAnInstructionToClear() {
        Deal deal = dealAt(DealStages.NEGOTIATION);
        deal.setPaymentTerms("Existing terms");
        when(dealRepository.findByIdAndOrganizationId(DEAL_ID, ORG_ID)).thenReturn(Optional.of(deal));

        var response = service.recordNegotiatedTerms(caller(), DEAL_ID,
                new NegotiatedTermsRequest(null, "   ", null));

        assertThat(response.paymentTerms()).isEqualTo("Existing terms");
    }

    @Test
    void refusesADealStillAtProposal() {
        dealIs(DealStages.PROPOSAL);

        assertThatThrownBy(() -> service.recordNegotiatedTerms(caller(), DEAL_ID,
                new NegotiatedTermsRequest(new BigDecimal("1000000.00"), null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(dealRepository, never()).save(any(Deal.class));
    }

    /**
     * Once the deal has reached the contract stage a contract may already exist,
     * and it snapshots its line items at generation — a later edit here would
     * not reach it, and the two records would disagree.
     */
    @Test
    void refusesADealAlreadyAtTheContractStage() {
        dealIs(DealStages.CONTRACT);

        assertThatThrownBy(() -> service.recordNegotiatedTerms(caller(), DEAL_ID,
                new NegotiatedTermsRequest(new BigDecimal("1000000.00"), null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aDealInAnotherOrganizationIsNotFound() {
        when(dealRepository.findByIdAndOrganizationId(anyLong(), anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordNegotiatedTerms(caller(), DEAL_ID,
                new NegotiatedTermsRequest(new BigDecimal("1000000.00"), null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
