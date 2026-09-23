package com.techcrm.crm.proposal;

import com.techcrm.crm.account.Account;
import com.techcrm.crm.account.AccountRepository;
import com.techcrm.crm.contact.Contact;
import com.techcrm.crm.contact.ContactRepository;
import com.techcrm.crm.deal.Deal;
import com.techcrm.crm.deal.DealRepository;
import com.techcrm.crm.deal.DealStages;
import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.lead.LeadRepository;
import com.techcrm.crm.org.Organization;
import com.techcrm.crm.org.OrganizationRepository;
import com.techcrm.crm.proposal.ProposalDtos.GenerateProposalRequest;
import com.techcrm.crm.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.techcrm.crm.proposal.ProposalFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * What the assembler refuses, and what it makes of the CRM data it accepts.
 *
 * The status codes matter as much as the rejections: the automation platform
 * retries a 409 once a rep has moved the stage, and never retries a 400.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProposalDataAssemblerTest {

    @Mock DealRepository dealRepository;
    @Mock AccountRepository accountRepository;
    @Mock ContactRepository contactRepository;
    @Mock UserRepository userRepository;
    @Mock LeadRepository leadRepository;
    @Mock OrganizationRepository organizationRepository;

    ProposalProperties properties;
    ProposalDataAssembler assembler;

    private static final GenerateProposalRequest PLAIN =
            new GenerateProposalRequest(DEAL_ID, null, null, null, null, null, null);

    @BeforeEach
    void setUp() {
        properties = new ProposalProperties();
        assembler = new ProposalDataAssembler(dealRepository, accountRepository, contactRepository,
                userRepository, leadRepository, organizationRepository, properties);

        when(dealRepository.findByIdAndOrganizationId(DEAL_ID, ORG_ID)).thenReturn(Optional.of(deal()));
        when(accountRepository.findByIdAndOrganizationId(ACCOUNT_ID, ORG_ID)).thenReturn(Optional.of(account()));
        when(contactRepository.findForAccount(ACCOUNT_ID, ORG_ID)).thenReturn(List.of(contact()));
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner()));
        when(leadRepository.findById(anyLong())).thenReturn(Optional.of(lead()));

        Organization org = new Organization();
        org.setId(ORG_ID);
        org.setName("TechCRM Solutions");
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));
    }

    @Test
    void anUnknownDealIsA404() {
        when(dealRepository.findByIdAndOrganizationId(999L, ORG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assembler.requireDeal(caller(), 999L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Nested
    class StageEligibility {

        /** The trigger for this bot. */
        @Test
        void aDealInTheProposalStageIsEligible() {
            assertThat(assembler.assemble(caller(), deal(), PLAIN)).isNotNull();
        }

        /**
         * The distinction the spec is explicit about: this bot fires when a deal
         * *reaches* Proposal. "Proposal Accepted" is a later outcome and belongs
         * to the contract bot, so the default list is PROPOSAL alone — narrower
         * than the contract module's.
         */
        @Test
        void aDealPastTheProposalStageIsRejectedWith409() {
            Deal negotiating = deal();
            negotiating.setStage(DealStages.NEGOTIATION);

            assertThatThrownBy(() -> assembler.assemble(caller(), negotiating, PLAIN))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void anEarlierStageIsAlsoRejected() {
            Deal early = deal();
            early.setStage(DealStages.QUALIFICATION);

            assertThatThrownBy(() -> assembler.assemble(caller(), early, PLAIN))
                    .isInstanceOf(ResponseStatusException.class);
        }

        /** Configuration, not code, so a team that works its pipeline
         *  differently does not need a rebuild. */
        @Test
        void theEligibleStageListIsConfigurable() {
            properties.setEligibleStages(List.of("NEGOTIATION"));
            Deal negotiating = deal();
            negotiating.setStage(DealStages.NEGOTIATION);

            assertThat(assembler.assemble(caller(), negotiating, PLAIN)).isNotNull();
        }

        /** A typo would otherwise make every deal permanently ineligible with no
         *  other symptom, so it fails loudly and names the bad value. */
        @Test
        void aStageThatDoesNotExistIsAConfigurationError() {
            properties.setEligibleStages(List.of("PROPOSAL_ACCEPTED"));

            assertThatThrownBy(() -> assembler.assemble(caller(), deal(), PLAIN))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PROPOSAL_ACCEPTED");
        }
    }

    @Nested
    class MissingCustomerData {

        @Test
        void aMissingAccountIsA400() {
            when(accountRepository.findByIdAndOrganizationId(ACCOUNT_ID, ORG_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> assembler.assemble(caller(), deal(), PLAIN))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void anAccountWithNoBillingAddressCannotBeQuoted() {
            Account noAddress = account();
            noAddress.setBillingAddress(null);
            when(accountRepository.findByIdAndOrganizationId(ACCOUNT_ID, ORG_ID)).thenReturn(Optional.of(noAddress));

            assertThatThrownBy(() -> assembler.assemble(caller(), deal(), PLAIN))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("billing address");
        }

        /** A proposal with no route to a customer cannot progress past
         *  generation, so the absence is caught here rather than at send time. */
        @Test
        void anAccountWithNoEmailableContactIsRejected() {
            when(contactRepository.findForAccount(ACCOUNT_ID, ORG_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> assembler.assemble(caller(), deal(), PLAIN))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("no contact with an email address");
        }

        @Test
        void aContactWithoutAnEmailAddressDoesNotCount() {
            Contact noEmail = contact();
            noEmail.setEmail(null);
            when(contactRepository.findForAccount(ACCOUNT_ID, ORG_ID)).thenReturn(List.of(noEmail));

            assertThatThrownBy(() -> assembler.assemble(caller(), deal(), PLAIN))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        void anExplicitContactFromAnotherAccountIsRejected() {
            Contact other = contact();
            other.setAccountId(999L);
            when(contactRepository.findByIdAndOrganizationId(CONTACT_ID, ORG_ID)).thenReturn(Optional.of(other));

            var request = new GenerateProposalRequest(DEAL_ID, null, CONTACT_ID, null, null, null, null);

            assertThatThrownBy(() -> assembler.assemble(caller(), deal(), request))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("does not belong to this deal's account");
        }

        @Test
        void aDealWithNoOwnerHasNobodyToNameOnTheProposal() {
            Deal unowned = deal();
            unowned.setOwnerId(null);

            assertThatThrownBy(() -> assembler.assemble(caller(), unowned, PLAIN))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("no owner");
        }

        /** The cross-organization case: an owner who exists but belongs to a
         *  different tenant must not be reachable through this deal. */
        @Test
        void anOwnerInAnotherOrganizationIsNotAccepted() {
            var foreign = owner();
            foreign.setOrganizationId(999L);
            when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(foreign));

            assertThatThrownBy(() -> assembler.assemble(caller(), deal(), PLAIN))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("no longer exists");
        }
    }

    @Nested
    class Pricing {

        @Test
        void aProductOnTheOriginatingLeadBecomesAPricedLine() {
            Deal fromLead = deal();
            fromLead.setLeadId(LEAD_ID);

            var assembly = assembler.assemble(caller(), fromLead, PLAIN);

            assertThat(assembly.lineItems()).hasSize(1);
            var line = assembly.lineItems().get(0);
            assertThat(line.description()).isEqualTo("Fleet Telematics Unit");
            assertThat(line.quantity()).isEqualByComparingTo("30");
            assertThat(line.unitPrice()).isEqualByComparingTo("15000.00");
            assertThat(line.source()).isEqualTo(ProposalLineItem.Source.LEAD_PRODUCT);
        }

        /** The printed columns must multiply out. A quotation whose own
         *  arithmetic does not add up is the worse failure in front of a
         *  customer. */
        @Test
        void thePrintedArithmeticAddsUp() {
            Deal fromLead = deal();
            fromLead.setLeadId(LEAD_ID);

            var line = assembler.assemble(caller(), fromLead, PLAIN).lineItems().get(0);

            assertThat(line.unitPrice().multiply(line.quantity()))
                    .isEqualByComparingTo(line.lineTotal());
        }

        /** No product anywhere: one line for the deal itself, which is the CRM's
         *  authoritative number. */
        @Test
        void aDealWithNoLeadFallsBackToASingleDealValueLine() {
            var assembly = assembler.assemble(caller(), deal(), PLAIN);

            assertThat(assembly.lineItems()).hasSize(1);
            assertThat(assembly.lineItems().get(0).source()).isEqualTo(ProposalLineItem.Source.DEAL_VALUE);
            assertThat(assembly.lineItems().get(0).description()).isEqualTo("Northwind ERP rollout");
            assertThat(assembly.totalAmount()).isEqualByComparingTo("450000.00");
            assertThat(assembly.hasProductLines()).isFalse();
        }

        @Test
        void aLeadWithNoProductNamedAlsoFallsBack() {
            Lead noProduct = lead();
            noProduct.setProduct(null);
            when(leadRepository.findById(anyLong())).thenReturn(Optional.of(noProduct));
            Deal fromLead = deal();
            fromLead.setLeadId(LEAD_ID);

            var assembly = assembler.assemble(caller(), fromLead, PLAIN);

            assertThat(assembly.lineItems().get(0).source()).isEqualTo(ProposalLineItem.Source.DEAL_VALUE);
        }

        /** Zero is a legitimate stored quantity but cannot be priced, so it is
         *  rejected rather than silently treated as one. */
        @Test
        void aZeroQuantityCannotBePriced() {
            Lead none = lead();
            none.setProductQuantity(0);
            when(leadRepository.findById(anyLong())).thenReturn(Optional.of(none));
            Deal fromLead = deal();
            fromLead.setLeadId(LEAD_ID);

            assertThatThrownBy(() -> assembler.assemble(caller(), fromLead, PLAIN))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("cannot be priced");
        }

        @Test
        void aDealWorthNothingCannotBeQuoted() {
            Deal free = deal();
            free.setValue(BigDecimal.ZERO);

            assertThatThrownBy(() -> assembler.assemble(caller(), free, PLAIN))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("greater than zero");
        }
    }

    @Nested
    class Validity {

        /** The difference between a proposal and a contract: an offer expires. */
        @Test
        void defaultsToTheConfiguredValidityWindow() {
            properties.setDefaultValidityDays(45);

            var assembly = assembler.assemble(caller(), deal(), PLAIN);

            assertThat(assembly.validUntil()).isEqualTo(LocalDate.now().plusDays(45));
        }

        @Test
        void anExplicitExpiryOverridesTheDefault() {
            LocalDate expiry = LocalDate.now().plusDays(7);
            var request = new GenerateProposalRequest(DEAL_ID, null, null, expiry, null, null, null);

            assertThat(assembler.assemble(caller(), deal(), request).validUntil()).isEqualTo(expiry);
        }

        /** Sending a customer a quotation that has already expired is worse than
         *  refusing to generate one. */
        @Test
        void anExpiryInThePastIsRejected() {
            var request = new GenerateProposalRequest(
                    DEAL_ID, null, null, LocalDate.now().minusDays(1), null, null, null);

            assertThatThrownBy(() -> assembler.assemble(caller(), deal(), request))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("already expired");
        }

        @Test
        void todayIsNotAValidExpiryEither() {
            var request = new GenerateProposalRequest(
                    DEAL_ID, null, null, LocalDate.now(), null, null, null);

            assertThatThrownBy(() -> assembler.assemble(caller(), deal(), request))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Nested
    class Terms {

        @Test
        void termsDefaultFromConfigurationBecauseTheCrmRecordsNone() {
            var assembly = assembler.assemble(caller(), deal(), PLAIN);

            assertThat(assembly.paymentTerms()).isEqualTo(properties.getDefaultPaymentTerms());
            assertThat(assembly.deliveryTimeline()).isEqualTo(properties.getDefaultDeliveryTimeline());
        }

        @Test
        void requestValuesWinOverTheDefaults() {
            var request = new GenerateProposalRequest(
                    DEAL_ID, null, null, null, "Net 60", "Q1 2027", null);

            var assembly = assembler.assemble(caller(), deal(), request);

            assertThat(assembly.paymentTerms()).isEqualTo("Net 60");
            assertThat(assembly.deliveryTimeline()).isEqualTo("Q1 2027");
        }

        /** The originating lead's purchase timeline is the only delivery
         *  expectation the CRM actually records, so it beats the default. */
        @Test
        void theLeadsPurchaseTimelineBeatsTheConfiguredDefault() {
            Deal fromLead = deal();
            fromLead.setLeadId(LEAD_ID);

            var assembly = assembler.assemble(caller(), fromLead, PLAIN);

            assertThat(assembly.deliveryTimeline())
                    .isEqualTo("Delivery expected within 1 month from proposal acceptance.");
        }
    }
}
