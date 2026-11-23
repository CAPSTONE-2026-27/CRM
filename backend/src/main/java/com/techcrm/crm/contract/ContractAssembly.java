package com.techcrm.crm.contract;

import com.techcrm.crm.account.Account;
import com.techcrm.crm.contact.Contact;
import com.techcrm.crm.deal.Deal;
import com.techcrm.crm.user.User;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The validated CRM data a contract is built from, gathered once so the
 * template resolver, the document renderer and the persistence step all see the
 * same snapshot. Produced by {@link ContractDataAssembler}; by the time one of
 * these exists every mandatory field has already been checked.
 */
public record ContractAssembly(
        Deal deal,
        Account account,
        Contact contact,
        User owner,
        String providerName,
        List<ResolvedLineItem> lineItems,
        String currency,
        BigDecimal totalAmount,
        LocalDate startDate,
        LocalDate endDate,
        String paymentTerms,
        String deliveryTimeline
) {

    /**
     * One schedule line, before it is persisted.
     *
     * @param source where it came from — see {@link ContractLineItem.Source}
     */
    public record ResolvedLineItem(
            int lineNumber,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            ContractLineItem.Source source
    ) {
    }

    /** True when the schedule is a real product line rather than the
     *  deal-value fallback — the signal the template resolver keys off. */
    public boolean hasProductLines() {
        return lineItems.stream().anyMatch(i -> i.source() == ContractLineItem.Source.LEAD_PRODUCT);
    }

    public int termMonths() {
        if (startDate == null || endDate == null) {
            return 0;
        }
        return (int) java.time.temporal.ChronoUnit.MONTHS.between(startDate, endDate);
    }
}
