package com.techcrm.crm.contract;

import com.techcrm.crm.account.AccountRepository;
import com.techcrm.crm.contact.Contact;
import com.techcrm.crm.contact.ContactRepository;
import com.techcrm.crm.contract.ContractDtos.GenerateContractRequest;
import com.techcrm.crm.deal.Deal;
import com.techcrm.crm.deal.DealRepository;
import com.techcrm.crm.deal.DealStages;
import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.lead.LeadRepository;
import com.techcrm.crm.org.Organization;
import com.techcrm.crm.org.OrganizationRepository;
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

import static com.techcrm.crm.contract.ContractFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * The validation gate. Each test breaks exactly one thing about an otherwise
 * valid deal and asserts on the status code, because the status is the part the
 * automation platform branches on: 409 means "come back after the rep moves the
 * stage", 400 means "this will never work as it stands".
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContractDataAssemblerTest {

    @Mock DealRepository dealRepository;
    @Mock AccountRepository accountRepository;
    @Mock ContactRepository contactRepository;
    @Mock UserRepository userRepository;
    @Mock LeadRepository leadRepository;
    @Mock OrganizationRepository organizationRepository;

    ContractProperties properties;
    ContractDataAssembler assembler;

    private static final GenerateContractRequest BARE =
            new GenerateContractRequest(DEAL_ID, null, null, null, null, null, null, null);

    @BeforeEach
    void setUp() {
        properties = new ContractProperties();
        assembler = new ContractDataAssembler(dealRepository, accountRepository, contactRepository,
                userRepository, leadRepository, organizationRepository, properties);

        Organization org = new Organization();
        org.setId(ORG_ID);
        org.setName("TechCRM Solutions");

        when(dealRepository.findByIdAndOrganizationId(DEAL_ID, ORG_ID)).thenReturn(Optional.of(deal()));
        when(accountRepository.findByIdAndOrganizationId(ACCOUNT_ID, ORG_ID)).thenReturn(Optional.of(account()));
        when(contactRepository.findForAccount(ACCOUNT_ID, ORG_ID)).thenReturn(List.of(contact()));
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner()));
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(org));
        when(leadRepository.findById(anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void assemblesAValidDeal() {
        ContractAssembly assembly = assembler.assemble(caller(), deal(), BARE);

        assertThat(assembly.account().getName()).isEqualTo("Northwind Traders Pvt Ltd");
        assertThat(assembly.contact().getEmail()).isEqualTo("asha.menon@northwind.example");
        assertThat(assembly.owner().getFullName()).isEqualTo("Ravi Kulkarni");
        assertThat(assembly.currency()).isEqualTo("INR");
        assertThat(assembly.totalAmount()).isEqualByComparingTo("450000.00");
        assertThat(assembly.providerName()).isEqualTo("TechCRM Solutions");
    }

    @Test
    void defaultsTheTermToTwelveMonthsFromToday() {
        ContractAssembly assembly = assembler.assemble(caller(), deal(), BARE);

        assertThat(assembly.startDate()).isEqualTo(LocalDate.now());
        assertThat(assembly.endDate()).isEqualTo(LocalDate.now().plusMonths(12));
        assertThat(assembly.termMonths()).isEqualTo(12);
    }

    @Test
    void requireDealRejectsAnUnknownId() {
        when(dealRepository.findByIdAndOrganizationId(999L, ORG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assembler.requireDeal(caller(), 999L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** A deal in another organization is invisible, not forbidden — the caller
     *  must not be able to prove it exists. */
    @Test
    void requireDealDoesNotLeakOtherOrganizationsDeals() {
        when(dealRepository.findByIdAndOrganizationId(DEAL_ID, 999L)).thenReturn(Optional.empty());
        var otherOrgCaller = new com.techcrm.crm.auth.AuthenticatedUser(
                1L, 999L, com.techcrm.crm.user.Role.SALES_REP, List.of(), false);

        assertThatThrownBy(() -> assembler.requireDeal(otherOrgCaller, DEAL_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Deal not found");
    }

    @Nested
    class StageEligibility {

        @Test
        void rejectsADealThatIsNotYetAtProposal() {
            Deal early = deal();
            early.setStage(DealStages.QUALIFICATION);

            assertThatThrownBy(() -> assembler.assemble(caller(), early, BARE))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void acceptsEveryConfiguredStage() {
            for (String stage : List.of(DealStages.PROPOSAL, DealStages.NEGOTIATION, DealStages.CLOSED_WON)) {
                Deal deal = deal();
                deal.setStage(stage);
                assertThat(assembler.assemble(caller(), deal, BARE)).isNotNull();
            }
        }

        @Test
        void aStageThatDoesNotExistIsAConfigurationErrorNotABadRequest() {
            properties.setEligibleStages(List.of("PROPOSAL_ACCEPTED"));

            assertThatThrownBy(() -> assembler.assemble(caller(), deal(), BARE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PROPOSAL_ACCEPTED")
                    .hasMessageContaining("not a deal stage");
        }
    }

    @Nested
    class MissingCustomerData {

        @Test
        void rejectsADealWhoseAccountHasVanished() {
            when(accountRepository.findByIdAndOrganizationId(ACCOUNT_ID, ORG_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> assembler.assemble(caller(), deal(), BARE))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Account not found");
        }

        @Test
        void rejectsAnAccountWithNoBillingAddress() {
            var account = account();
            account.setBillingAddress("  ");
            when(accountRepository.findByIdAndOrganizationId(ACCOUNT_ID, ORG_ID)).thenReturn(Optional.of(account));

            assertThatThrownBy(() -> assembler.assemble(caller(), deal(), BARE))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void rejectsAnAccountWithNoContactAtAll() {
            when(contactRepository.findForAccount(ACCOUNT_ID, ORG_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> assembler.assemble(caller(), deal(), BARE))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("no contact with an email address");
        }

        /** A contact with no email cannot be sent a document, so it does not
         *  count as a signer even though it exists. */
        @Test
        void skipsContactsWithNoEmailAndFallsBackToOneThatHasOne() {
            Contact noEmail = contact();
            noEmail.setId(99L);
            noEmail.setEmail(null);

            when(contactRepository.findForAccount(ACCOUNT_ID, ORG_ID)).thenReturn(List.of(noEmail, contact()));

            assertThat(assembler.assemble(caller(), deal(), BARE).contact().getId()).isEqualTo(CONTACT_ID);
        }

        @Test
        void rejectsAnExplicitContactFromADifferentAccount() {
            Contact foreign = contact();
            foreign.setId(77L);
            foreign.setAccountId(4321L);
            when(contactRepository.findByIdAndOrganizationId(77L, ORG_ID)).thenReturn(Optional.of(foreign));

            var request = new GenerateContractRequest(DEAL_ID, null, 77L, null, null, null, null, null);

            assertThatThrownBy(() -> assembler.assemble(caller(), deal(), request))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("does not belong to this deal's account");
        }

        @Test
        void rejectsADealWithNoOwnerToNameAsSalesExecutive() {
            Deal unowned = deal();
            unowned.setOwnerId(null);

            assertThatThrownBy(() -> assembler.assemble(caller(), unowned, BARE))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Assign a sales executive");
        }
    }

    @Nested
    class LineItems {

        /** No originating lead means no product anywhere in the CRM, so the
         *  schedule falls back to a single line for the deal. */
        @Test
        void fallsBackToASingleDealValueLine() {
            ContractAssembly assembly = assembler.assemble(caller(), deal(), BARE);

            assertThat(assembly.lineItems()).singleElement().satisfies(item -> {
                assertThat(item.description()).isEqualTo("Northwind ERP rollout");
                assertThat(item.quantity()).isEqualByComparingTo("1");
                assertThat(item.lineTotal()).isEqualByComparingTo("450000.00");
                assertThat(item.source()).isEqualTo(ContractLineItem.Source.DEAL_VALUE);
            });
            assertThat(assembly.hasProductLines()).isFalse();
        }

        @Test
        void usesTheOriginatingLeadsProductAndQuantity() {
            Deal converted = deal();
            converted.setLeadId(LEAD_ID);
            when(leadRepository.findById(LEAD_ID)).thenReturn(Optional.of(lead()));

            ContractAssembly assembly = assembler.assemble(caller(), converted, BARE);

            assertThat(assembly.lineItems()).singleElement().satisfies(item -> {
                assertThat(item.description()).isEqualTo("Fleet Telematics Unit");
                assertThat(item.quantity()).isEqualByComparingTo("30");
                assertThat(item.unitPrice()).isEqualByComparingTo("15000.00");
                assertThat(item.lineTotal()).isEqualByComparingTo("450000.00");
                assertThat(item.source()).isEqualTo(ContractLineItem.Source.LEAD_PRODUCT);
            });
            assertThat(assembly.hasProductLines()).isTrue();
        }

        /** The printed columns have to multiply out. Rounding the unit price and
         *  recomputing the total is what guarantees that, at the cost of a few
         *  paise against the deal value. */
        @Test
        void theScheduleAlwaysAddsUp() {
            Deal converted = deal();
            converted.setLeadId(LEAD_ID);
            converted.setValue(new BigDecimal("100000.00"));
            Lead awkward = lead();
            awkward.setProductQuantity(3);
            when(leadRepository.findById(LEAD_ID)).thenReturn(Optional.of(awkward));

            ContractAssembly assembly = assembler.assemble(caller(), converted, BARE);
            var item = assembly.lineItems().get(0);

            assertThat(item.unitPrice()).isEqualByComparingTo("33333.33");
            assertThat(item.unitPrice().multiply(item.quantity())).isEqualByComparingTo(item.lineTotal());
            assertThat(assembly.totalAmount()).isEqualByComparingTo(item.lineTotal());
        }

        @Test
        void rejectsADealWorthNothing() {
            Deal worthless = deal();
            worthless.setValue(BigDecimal.ZERO);

            assertThatThrownBy(() -> assembler.assemble(caller(), worthless, BARE))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("greater than zero");
        }

        @Test
        void rejectsAProductWithAnUnpriceableQuantity() {
            Deal converted = deal();
            converted.setLeadId(LEAD_ID);
            Lead none = lead();
            none.setProductQuantity(0);
            when(leadRepository.findById(LEAD_ID)).thenReturn(Optional.of(none));

            assertThatThrownBy(() -> assembler.assemble(caller(), converted, BARE))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("cannot be priced");
        }
    }

    @Nested
    class Terms {

        @Test
        void rejectsAnEndDateThatIsNotAfterTheStart() {
            var request = new GenerateContractRequest(DEAL_ID, null, null,
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1), null, null, null);

            assertThatThrownBy(() -> assembler.assemble(caller(), deal(), request))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("must be after the start date");
        }

        @Test
        void requestTermsBeatTheConfiguredDefaults() {
            var request = new GenerateContractRequest(DEAL_ID, null, null, null, null,
                    "Net 45", "Ships in 5 working days", null);

            ContractAssembly assembly = assembler.assemble(caller(), deal(), request);

            assertThat(assembly.paymentTerms()).isEqualTo("Net 45");
            assertThat(assembly.deliveryTimeline()).isEqualTo("Ships in 5 working days");
        }

        /** The lead's purchase timeline is the only delivery expectation the CRM
         *  records, so it outranks the configured fallback. */
        @Test
        void theLeadsPurchaseTimelineBeatsTheConfiguredDefault() {
            Deal converted = deal();
            converted.setLeadId(LEAD_ID);
            when(leadRepository.findById(LEAD_ID)).thenReturn(Optional.of(lead()));

            ContractAssembly assembly = assembler.assemble(caller(), converted, BARE);

            assertThat(assembly.deliveryTimeline()).contains("within 1 month");
        }

        @Test
        void fallsBackToTheConfiguredDefaults() {
            ContractAssembly assembly = assembler.assemble(caller(), deal(), BARE);

            assertThat(assembly.paymentTerms()).isEqualTo(properties.getDefaultPaymentTerms());
            assertThat(assembly.deliveryTimeline()).isEqualTo(properties.getDefaultDeliveryTimeline());
        }
    }

    @Test
    void doesNotConsultAccountsOutsideTheCallersOrganization() {
        // The assembler only ever calls the organization-scoped finders; this
        // pins that, because a plain findById here would silently cross tenants.
        assembler.assemble(caller(), deal(), BARE);

        org.mockito.Mockito.verify(accountRepository).findByIdAndOrganizationId(ACCOUNT_ID, ORG_ID);
        org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never()).findById(any());
    }
}
