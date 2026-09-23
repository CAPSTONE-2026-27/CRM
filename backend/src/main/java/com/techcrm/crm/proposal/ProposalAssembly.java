package com.techcrm.crm.proposal;

import com.techcrm.crm.account.Account;
import com.techcrm.crm.contact.Contact;
import com.techcrm.crm.deal.Deal;
import com.techcrm.crm.user.User;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The validated CRM data a proposal is built from, gathered once so the template
 * resolver, the document renderer and the persistence step all see the same
 * snapshot. Produced by {@link ProposalDataAssembler}; by the time one of these
 * exists every mandatory field has already been checked.
 */
public record ProposalAssembly(
        Deal deal,
        Account account,
        Contact contact,
        User owner,
        String providerName,
        List<ResolvedLineItem> lineItems,
        String currency,
        BigDecimal totalAmount,
        LocalDate validUntil,
        String paymentTerms,
        String deliveryTimeline
) {

    /**
     * One priced line, before it is persisted.
     *
     * @param source where it came from — see {@link ProposalLineItem.Source}
     */
    public record ResolvedLineItem(
            int lineNumber,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            ProposalLineItem.Source source
    ) {
    }

    /** True when the pricing is a real product line rather than the deal-value
     *  fallback — the signal the template resolver keys off. */
    public boolean hasProductLines() {
        return lineItems.stream().anyMatch(i -> i.source() == ProposalLineItem.Source.LEAD_PRODUCT);
    }

    /** Days the quotation stands from today. Printed on the document so the
     *  customer sees the deadline, not just the date. */
    public int validityDays() {
        if (validUntil == null) {
            return 0;
        }
        return (int) java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), validUntil);
    }
}
