package com.techcrm.crm.proposal;

import com.techcrm.crm.account.Account;
import com.techcrm.crm.account.AccountRepository;
import com.techcrm.crm.auth.AuthenticatedUser;
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
import com.techcrm.crm.user.User;
import com.techcrm.crm.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Fetches and validates everything a proposal needs, in one pass.
 *
 * This is where "the deal is not ready to be quoted" gets decided. Nothing
 * downstream re-checks: a {@link ProposalAssembly} coming out of here is complete
 * by construction, so the document renderer never has to decide what to do about
 * a missing customer address.
 *
 * Failures use the same convention as the rest of the CRM — a
 * {@code ResponseStatusException} that {@code ApiExceptionHandler} turns into an
 * {@code {"error": "..."}} body — with 404 for a deal that isn't there, 409 for a
 * deal that exists but isn't ready, and 400 for one whose data is incomplete.
 * That split matters to the automation platform: a 409 is worth retrying once
 * the rep moves the stage, a 400 never is.
 */
@Service
public class ProposalDataAssembler {

    private final DealRepository dealRepository;
    private final AccountRepository accountRepository;
    private final ContactRepository contactRepository;
    private final UserRepository userRepository;
    private final LeadRepository leadRepository;
    private final OrganizationRepository organizationRepository;
    private final ProposalProperties properties;

    public ProposalDataAssembler(DealRepository dealRepository,
                                 AccountRepository accountRepository,
                                 ContactRepository contactRepository,
                                 UserRepository userRepository,
                                 LeadRepository leadRepository,
                                 OrganizationRepository organizationRepository,
                                 ProposalProperties properties) {
        this.dealRepository = dealRepository;
        this.accountRepository = accountRepository;
        this.contactRepository = contactRepository;
        this.userRepository = userRepository;
        this.leadRepository = leadRepository;
        this.organizationRepository = organizationRepository;
        this.properties = properties;
    }

    /**
     * Loads the deal, or 404s. Separate from {@link #assemble} so the idempotency
     * check can run against a known-good deal before the much more expensive
     * validation does.
     */
    @Transactional(readOnly = true)
    public Deal requireDeal(AuthenticatedUser caller, Long dealId) {
        return dealRepository.findByIdAndOrganizationId(dealId, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deal not found"));
    }

    @Transactional(readOnly = true)
    public ProposalAssembly assemble(AuthenticatedUser caller, Deal deal, GenerateProposalRequest request) {
        requireEligibleStage(deal);

        Account account = accountRepository.findByIdAndOrganizationId(deal.getAccountId(), caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Account not found for this deal"));

        if (isBlank(account.getBillingAddress())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Account \"" + account.getName() + "\" has no billing address. "
                            + "A proposal cannot be addressed without one.");
        }

        Contact contact = resolveContact(caller, account, request.contactId());
        User owner = resolveOwner(caller, deal);

        List<ProposalAssembly.ResolvedLineItem> lineItems = resolveLineItems(deal);
        BigDecimal totalAmount = lineItems.stream()
                .map(ProposalAssembly.ResolvedLineItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDate validUntil = request.validUntil() != null
                ? request.validUntil()
                : LocalDate.now().plusDays(properties.getDefaultValidityDays());
        if (!validUntil.isAfter(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Proposal validity must be a future date - a quotation that has already expired "
                            + "cannot be sent to a customer");
        }

        String providerName = organizationRepository.findById(caller.organizationId())
                .map(Organization::getName)
                .orElse("");

        return new ProposalAssembly(
                deal, account, contact, owner, providerName, lineItems,
                deal.getCurrency(), totalAmount, validUntil,
                firstNonBlank(request.paymentTerms(), properties.getDefaultPaymentTerms()),
                firstNonBlank(request.deliveryTimeline(), deliveryTimelineFromLead(deal),
                        properties.getDefaultDeliveryTimeline()));
    }

    /**
     * The stage gate.
     *
     * {@code proposal.eligible-stages} defaults to PROPOSAL alone. That is
     * narrower than the contract module's list on purpose: this bot is triggered
     * by the deal <em>reaching</em> the Proposal stage, whereas the contract bot
     * fires on the later "Proposal Accepted" outcome. The two must not be
     * confused — quoting a deal that is already in NEGOTIATION would put a fresh
     * price in front of a customer who is arguing about the previous one.
     */
    private void requireEligibleStage(Deal deal) {
        Set<String> eligible = eligibleStages();
        if (!eligible.contains(deal.getStage())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Deal is in stage " + deal.getStage() + " and is not ready for a proposal. "
                            + "Eligible stages: " + String.join(", ", eligible));
        }
    }

    /**
     * Validated on use rather than at startup, so a typo in configuration
     * surfaces as a clear error naming the bad value instead of silently making
     * every deal ineligible.
     */
    private Set<String> eligibleStages() {
        Set<String> stages = new LinkedHashSet<>();
        for (String raw : properties.getEligibleStages()) {
            String stage = raw == null ? "" : raw.trim().toUpperCase().replace(' ', '_');
            if (!DealStages.ALL.contains(stage)) {
                throw new IllegalStateException(
                        "proposal.eligible-stages contains \"" + raw + "\", which is not a deal stage. "
                                + "Valid stages: " + String.join(", ", DealStages.ORDERED));
            }
            stages.add(stage);
        }
        if (stages.isEmpty()) {
            throw new IllegalStateException("proposal.eligible-stages is empty - no deal could ever be proposed");
        }
        return stages;
    }

    /**
     * Picks the person the proposal is addressed to, and who later signs it.
     *
     * An explicit contactId wins. Otherwise the account's primary contact, and
     * failing that its oldest — in both cases only one that has an email address,
     * because a proposal with no route to a customer cannot progress past
     * generation.
     */
    private Contact resolveContact(AuthenticatedUser caller, Account account, Long requestedContactId) {
        if (requestedContactId != null) {
            Contact contact = contactRepository
                    .findByIdAndOrganizationId(requestedContactId, caller.organizationId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contact not found"));
            if (!contact.getAccountId().equals(account.getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Contact " + requestedContactId + " does not belong to this deal's account");
            }
            if (isBlank(contact.getEmail())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Contact \"" + contact.getFullName() + "\" has no email address");
            }
            return contact;
        }

        return contactRepository.findForAccount(account.getId(), caller.organizationId()).stream()
                .filter(c -> !isBlank(c.getEmail()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Account \"" + account.getName() + "\" has no contact with an email address. "
                                + "Add one before generating a proposal."));
    }

    /** The sales executive named on the document. A deal nobody owns cannot
     *  produce a proposal that says who to talk to about it. */
    private User resolveOwner(AuthenticatedUser caller, Deal deal) {
        if (deal.getOwnerId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Deal has no owner. Assign a sales executive before generating a proposal.");
        }
        return userRepository.findById(deal.getOwnerId())
                .filter(u -> caller.organizationId().equals(u.getOrganizationId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Deal owner " + deal.getOwnerId() + " no longer exists"));
    }

    /**
     * Builds the proposal's pricing from what the CRM actually records.
     *
     * There is no product entity in this CRM and this does not invent one — the
     * same constraint the contract module works under. The only product data that
     * exists is on the lead a deal was converted from ({@code leads.product},
     * {@code leads.product_quantity}); when the deal did not come from a lead, or
     * that lead named no product, the pricing is a single line for the deal
     * itself. Either way the money is the deal's own value, which is the CRM's
     * authoritative number.
     *
     * There is likewise no discount and no tax anywhere in the schema, so the
     * quotation prints neither. A zero-valued Discount row on a customer-facing
     * document invites the question of why it is there.
     *
     * The unit price is derived by division and the line total recomputed from
     * that rounded figure, rather than the deal value being copied straight into
     * the total, so the printed arithmetic adds up. On an awkward quantity that
     * can leave the proposal a few paise under the deal value; a quotation whose
     * own columns do not sum is the worse failure in front of a customer.
     */
    private List<ProposalAssembly.ResolvedLineItem> resolveLineItems(Deal deal) {
        BigDecimal value = deal.getValue();
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Deal value must be greater than zero to generate a proposal");
        }

        Optional<Lead> lead = deal.getLeadId() == null
                ? Optional.empty()
                : leadRepository.findById(deal.getLeadId());

        String product = lead.map(Lead::getProduct).filter(p -> !isBlank(p)).orElse(null);
        if (product == null) {
            BigDecimal amount = value.setScale(2, RoundingMode.HALF_UP);
            return List.of(new ProposalAssembly.ResolvedLineItem(
                    1, deal.getName(), BigDecimal.ONE.setScale(2, RoundingMode.HALF_UP),
                    amount, amount, ProposalLineItem.Source.DEAL_VALUE));
        }

        Integer rawQuantity = lead.map(Lead::getProductQuantity).orElse(null);
        // Zero is a legitimate stored value ("recorded, and it is none" - see the
        // check constraint in V17), but it cannot be priced, so it is rejected
        // rather than silently treated as one unit.
        if (rawQuantity != null && rawQuantity <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Lead " + deal.getLeadId() + " records a quantity of " + rawQuantity
                            + " for \"" + product + "\", which cannot be priced");
        }

        BigDecimal quantity = BigDecimal.valueOf(rawQuantity == null ? 1 : rawQuantity);
        BigDecimal unitPrice = value.divide(quantity, 2, RoundingMode.HALF_UP);
        BigDecimal lineTotal = unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);

        return List.of(new ProposalAssembly.ResolvedLineItem(
                1, product, quantity.setScale(2, RoundingMode.HALF_UP), unitPrice, lineTotal,
                ProposalLineItem.Source.LEAD_PRODUCT));
    }

    /**
     * The originating lead's purchase timeline is the only delivery expectation
     * the CRM records, so it beats the configured default when present.
     */
    private String deliveryTimelineFromLead(Deal deal) {
        if (deal.getLeadId() == null) {
            return null;
        }
        return leadRepository.findById(deal.getLeadId())
                .map(Lead::getPurchaseTimeline)
                .filter(t -> !isBlank(t))
                .map(t -> "Delivery expected " + t.toLowerCase() + " from proposal acceptance.")
                .orElse(null);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
