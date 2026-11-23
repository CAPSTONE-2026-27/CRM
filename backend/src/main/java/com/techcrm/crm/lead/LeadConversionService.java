package com.techcrm.crm.lead;

import com.techcrm.crm.account.Account;
import com.techcrm.crm.account.AccountRepository;
import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.contact.Contact;
import com.techcrm.crm.contact.ContactRepository;
import com.techcrm.crm.deal.Deal;
import com.techcrm.crm.deal.DealRepository;
import com.techcrm.crm.deal.DealStages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Lead flow step 6 — turns a lead into an opportunity.
 *
 * One lead becomes three records, the standard lead-conversion split: the
 * company becomes an Account, the person becomes a Contact, and the sale
 * becomes a Deal whose primary contact is that person. Existing accounts (by
 * name) and contacts (by email on that account) are reused rather than
 * duplicated. The lead's billing address fills an account that has none.
 * Together that is everything proposal and contract generation require, so a
 * converted deal can be quoted without anyone editing the account first.
 *
 * The lead is not consumed: it keeps its history and gains a pointer to the deal
 * it produced. That link is what lets a deal's score be traced back to the lead
 * score it started from, which the sales manager's view depends on.
 *
 * Conversion is idempotent by refusal rather than by silence: a second attempt
 * returns 409 with the existing deal's reference instead of quietly creating a
 * duplicate opportunity for the same customer.
 */
@Service
public class LeadConversionService {

    private static final Logger log = LoggerFactory.getLogger(LeadConversionService.class);

    private final LeadRepository leadRepository;
    private final DealRepository dealRepository;
    private final AccountRepository accountRepository;
    private final ContactRepository contactRepository;

    public LeadConversionService(LeadRepository leadRepository,
                                 DealRepository dealRepository,
                                 AccountRepository accountRepository,
                                 ContactRepository contactRepository) {
        this.leadRepository = leadRepository;
        this.dealRepository = dealRepository;
        this.accountRepository = accountRepository;
        this.contactRepository = contactRepository;
    }

    public record ConversionResult(Long dealId, String opportunityId, Long accountId, boolean accountCreated,
                                   Long contactId, boolean contactCreated) {
    }

    @Transactional
    public ConversionResult convert(AuthenticatedUser caller, Long leadId) {

        Lead lead = leadRepository.findByIdAndOrganizationId(leadId, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found"));

        if (lead.getConvertedDealId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This lead has already been converted to opportunity " + lead.getConvertedDealId());
        }

        // Qualification is the only gate. Conversion used to also require a
        // contact status of MEETING_SCHEDULED or INTERESTED, but the UI that set
        // contact status was removed along with flow step 5 — keeping the check
        // would have made every lead permanently unconvertible, since they all
        // sit at NOT_CONTACTED with no way to move them.
        if ("UNQUALIFIED".equals(lead.getQualificationStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only qualified leads can become opportunities.");
        }

        // Contact status is deliberately not a gate. It used to be — only a
        // lead marked "Meeting scheduled" or "Interested" could convert — but
        // the scoring model now decides readiness, and requiring both meant a
        // representative had to set a field by hand before the automation
        // would touch a lead the model had already called HOT.

        AccountLookup account = findOrCreateAccount(lead, caller.organizationId());
        ContactLookup contact = findOrCreateContact(lead, account.id(), caller.organizationId());

        Deal deal = new Deal();
        deal.setOrganizationId(caller.organizationId());
        deal.setName(opportunityName(lead));
        deal.setAccountId(account.id());
        deal.setValue(lead.getEstimatedDealValue() == null ? BigDecimal.ZERO : lead.getEstimatedDealValue());
        // Every converted lead lands on OPPORTUNITY_CREATED: on the board, the
        // columns before it hold leads, and conversion is the drag into it.
        // Meetings are booked on the deal afterwards (DealService), not here.
        deal.setStage(DealStages.OPPORTUNITY_CREATED);
        deal.setLeadId(lead.getId());
        deal.setPrimaryContactId(contact.id());
        // Carried across so the deal-scoring model starts from what the lead
        // model already concluded rather than from a default.
        deal.setLeadScore(lead.getAiScore() == null ? null : lead.getAiScore().doubleValue());
        deal.setOwnerId(lead.getAssignedToId());
        deal.setProbability(lead.getQualificationProbability() == null
                ? null
                : (int) Math.round(lead.getQualificationProbability()));

        Deal saved = dealRepository.save(deal);
        // The reference is derived from the id, so it can only be set after the
        // insert has allocated one.
        saved.setOpportunityId(DealStages.opportunityReference(saved.getId()));
        saved = dealRepository.save(saved);

        lead.setConvertedDealId(saved.getId());
        lead.setConvertedAt(OffsetDateTime.now());
        leadRepository.save(lead);

        log.info("Converted lead {} to opportunity {} (deal {})", lead.getId(), saved.getOpportunityId(), saved.getId());
        return new ConversionResult(saved.getId(), saved.getOpportunityId(), account.id(), account.created(),
                contact.id(), contact.created());
    }

    private record AccountLookup(Long id, boolean created) {
    }

    /**
     * A deal needs an account, and a lead only carries a company name. Matching
     * on that name is the pragmatic join: it reuses the existing account when
     * the customer is already known, and creates one when they aren't, rather
     * than forcing the rep to pick from a dropdown mid-conversion.
     */
    private AccountLookup findOrCreateAccount(Lead lead, Long organizationId) {
        String company = lead.getCompany() == null || lead.getCompany().isBlank()
                ? lead.getFullName()
                : lead.getCompany().trim();

        Account existing = accountRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId).stream()
                .filter(a -> a.getName() != null && a.getName().trim().equalsIgnoreCase(company))
                .findFirst()
                .orElse(null);

        if (existing != null) {
            // Fill a gap, never overwrite: an address already on the account
            // was entered deliberately and may be more current than the lead's.
            if (isBlank(existing.getBillingAddress()) && !isBlank(lead.getBillingAddress())) {
                existing.setBillingAddress(lead.getBillingAddress().trim());
                accountRepository.save(existing);
            }
            return new AccountLookup(existing.getId(), false);
        }

        Account account = new Account();
        account.setOrganizationId(organizationId);
        account.setName(company);
        account.setIndustry(lead.getIndustry());
        account.setEmployeeCount(lead.getEmployeeCount());
        account.setRelationshipValue(lead.getEstimatedDealValue());
        account.setOwnerId(lead.getAssignedToId());
        account.setBillingAddress(isBlank(lead.getBillingAddress()) ? null : lead.getBillingAddress().trim());
        return new AccountLookup(accountRepository.save(account).getId(), true);
    }

    private record ContactLookup(Long id, boolean created) {
    }

    /**
     * The lead's person, as a contact on the account.
     *
     * Matched by email, the one field that identifies a person; by name only
     * when the lead has no email. A second deal with the same customer then
     * reuses their contact instead of adding a duplicate. A new contact is
     * marked primary only when the account has no primary yet, so converting a
     * lead never demotes someone a rep already chose.
     */
    private ContactLookup findOrCreateContact(Lead lead, Long accountId, Long organizationId) {
        List<Contact> existing = contactRepository.findForAccount(accountId, organizationId);

        Contact match = existing.stream()
                .filter(c -> isBlank(lead.getEmail())
                        ? c.getFullName() != null && c.getFullName().trim().equalsIgnoreCase(lead.getFullName().trim())
                        : c.getEmail() != null && c.getEmail().trim().equalsIgnoreCase(lead.getEmail().trim()))
                .findFirst()
                .orElse(null);
        if (match != null) {
            return new ContactLookup(match.getId(), false);
        }

        Contact contact = new Contact();
        contact.setOrganizationId(organizationId);
        contact.setAccountId(accountId);
        contact.setFullName(lead.getFullName());
        contact.setEmail(isBlank(lead.getEmail()) ? null : lead.getEmail().trim());
        contact.setPhone(isBlank(lead.getPhone()) ? null : lead.getPhone().trim());
        contact.setPrimary(existing.stream().noneMatch(Contact::isPrimary));
        return new ContactLookup(contactRepository.save(contact).getId(), true);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String opportunityName(Lead lead) {
        String company = lead.getCompany() == null || lead.getCompany().isBlank() ? lead.getFullName() : lead.getCompany();
        String product = lead.getProduct() == null || lead.getProduct().isBlank() ? "Opportunity" : lead.getProduct();
        return company + " — " + product;
    }
}
