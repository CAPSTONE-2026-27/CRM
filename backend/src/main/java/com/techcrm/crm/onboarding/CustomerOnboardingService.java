package com.techcrm.crm.onboarding;

import com.techcrm.crm.auth.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * Deal flow step 14.
 *
 * Onboarding is initiated once per deal, however many times that deal is
 * re-saved as Closed Won — enforced by a unique index on deal_id as well as the
 * check here, since a race between two saves would otherwise slip past the
 * read-then-write.
 */
@Service
public class CustomerOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(CustomerOnboardingService.class);

    private static final Set<String> STATUSES = Set.of("INITIATED", "IN_PROGRESS", "COMPLETED", "CANCELLED");

    private final CustomerOnboardingRepository repository;

    public CustomerOnboardingService(CustomerOnboardingRepository repository) {
        this.repository = repository;
    }

    /** Returns the existing record when the deal has already been onboarded,
     *  so callers can treat this as idempotent. */
    @Transactional
    public CustomerOnboarding initiate(Long organizationId, Long dealId, String opportunityId,
                                       Long accountId, Long ownerId) {

        return repository.findByDealIdAndOrganizationId(dealId, organizationId).orElseGet(() -> {
            CustomerOnboarding onboarding = new CustomerOnboarding();
            onboarding.setOrganizationId(organizationId);
            onboarding.setDealId(dealId);
            onboarding.setOpportunityId(opportunityId);
            onboarding.setAccountId(accountId);
            onboarding.setOwnerId(ownerId);
            onboarding.setStatus("INITIATED");
            CustomerOnboarding saved = repository.save(onboarding);
            log.info("Initiated onboarding {} for won opportunity {}", saved.getId(), opportunityId);
            return saved;
        });
    }

    @Transactional(readOnly = true)
    public List<CustomerOnboarding> list(AuthenticatedUser caller) {
        return repository.findByOrganizationIdOrderByInitiatedAtDesc(caller.organizationId());
    }

    @Transactional(readOnly = true)
    public CustomerOnboarding findForDeal(AuthenticatedUser caller, Long dealId) {
        return repository.findByDealIdAndOrganizationId(dealId, caller.organizationId()).orElse(null);
    }

    @Transactional
    public CustomerOnboarding updateStatus(AuthenticatedUser caller, Long dealId, String rawStatus, String notes) {
        String status = rawStatus == null ? "" : rawStatus.trim().toUpperCase();
        if (!STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown onboarding status: " + rawStatus);
        }

        CustomerOnboarding onboarding = repository.findByDealIdAndOrganizationId(dealId, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "This deal has no onboarding record — it is not Closed Won yet."));

        onboarding.setStatus(status);
        if (notes != null) onboarding.setNotes(notes);
        onboarding.setCompletedAt("COMPLETED".equals(status) ? OffsetDateTime.now() : null);
        return repository.save(onboarding);
    }
}
