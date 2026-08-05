package com.techcrm.crm.lead;

import com.techcrm.crm.account.Account;
import com.techcrm.crm.account.AccountRepository;
import com.techcrm.crm.auth.AuthenticatedUser;
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

    /** Contact outcomes that mean the customer is willing to meet. Only these
     *  convert — "No Response" becoming an opportunity would make the pipeline
     *  a list of hopes rather than of deals. */
    private static final List<String> CONVERTIBLE_CONTACT_STATUSES =
            List.of("MEETING_SCHEDULED", "INTERESTED");

    private final LeadRepository leadRepository;
    private final DealRepository dealRepository;
    private final AccountRepository accountRepository;

    public LeadConversionService(LeadRepository leadRepository,
                                 DealRepository dealRepository,
                                 AccountRepository accountRepository) {
        this.leadRepository = leadRepository;
        this.dealRepository = dealRepository;
        this.accountRepository = accountRepository;
    }

    public record ConversionResult(Long dealId, String opportunityId, Long accountId, boolean accountCreated) {
    }

    @Transactional
    public ConversionResult convert(AuthenticatedUser caller, Long leadId, OffsetDateTime meetingScheduledAt,
                                    String meetingMode, String meetingParticipants) {

        Lead lead = leadRepository.findByIdAndOrganizationId(leadId, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found"));

        if (lead.getConvertedDealId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This lead has already been converted to opportunity " + lead.getConvertedDealId());
        }

        if ("UNQUALIFIED".equals(lead.getQualificationStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only qualified leads can become opportunities.");
        }

        if (!CONVERTIBLE_CONTACT_STATUSES.contains(lead.getContactStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The customer has not agreed to a meeting yet. Set the contact status to "
                            + "Meeting scheduled or Interested first.");
        }

        AccountLookup account = findOrCreateAccount(lead, caller.organizationId());

        Deal deal = new Deal();
        deal.setOrganizationId(caller.organizationId());
        deal.setName(opportunityName(lead));
        deal.setAccountId(account.id());
        deal.setValue(lead.getEstimatedDealValue() == null ? BigDecimal.ZERO : lead.getEstimatedDealValue());
        deal.setStage(DealStages.OPPORTUNITY_CREATED);
        deal.setLeadId(lead.getId());
        // Carried across so the deal-scoring model starts from what the lead
        // model already concluded rather than from a default.
        deal.setLeadScore(lead.getAiScore() == null ? null : lead.getAiScore().doubleValue());
        deal.setOwnerId(lead.getAssignedToId());
        deal.setProbability(lead.getQualificationProbability() == null
                ? null
                : (int) Math.round(lead.getQualificationProbability()));

        if (meetingScheduledAt != null) {
            deal.setMeetingScheduledAt(meetingScheduledAt);
            deal.setMeetingMode(meetingMode);
            deal.setMeetingParticipants(meetingParticipants);
            deal.setStage(DealStages.MEETING_SCHEDULED);
        }

        Deal saved = dealRepository.save(deal);
        // The reference is derived from the id, so it can only be set after the
        // insert has allocated one.
        saved.setOpportunityId(DealStages.opportunityReference(saved.getId()));
        saved = dealRepository.save(saved);

        lead.setConvertedDealId(saved.getId());
        lead.setConvertedAt(OffsetDateTime.now());
        leadRepository.save(lead);

        log.info("Converted lead {} to opportunity {} (deal {})", lead.getId(), saved.getOpportunityId(), saved.getId());
        return new ConversionResult(saved.getId(), saved.getOpportunityId(), account.id(), account.created());
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
            return new AccountLookup(existing.getId(), false);
        }

        Account account = new Account();
        account.setOrganizationId(organizationId);
        account.setName(company);
        account.setIndustry(lead.getIndustry());
        account.setEmployeeCount(lead.getEmployeeCount());
        account.setRelationshipValue(lead.getEstimatedDealValue());
        account.setOwnerId(lead.getAssignedToId());
        return new AccountLookup(accountRepository.save(account).getId(), true);
    }

    private String opportunityName(Lead lead) {
        String company = lead.getCompany() == null || lead.getCompany().isBlank() ? lead.getFullName() : lead.getCompany();
        String product = lead.getProduct() == null || lead.getProduct().isBlank() ? "Opportunity" : lead.getProduct();
        return company + " — " + product;
    }
}
