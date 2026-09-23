package com.techcrm.crm.proposal;

import com.techcrm.crm.account.Account;
import com.techcrm.crm.contact.Contact;
import com.techcrm.crm.deal.Deal;
import com.techcrm.crm.user.User;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a {@link ProposalAssembly} into the flat {@code name -> text} map the
 * proposal templates are written against.
 *
 * Every key here is backed by a field that actually exists in this CRM. In
 * particular there is no {@code {{discount}}} and no {@code {{taxAmount}}}: the
 * schema records neither a discount nor a tax rate anywhere, on the deal, the
 * account or the lead. Printing a zero-valued Discount row on a customer-facing
 * quotation would be worse than omitting the row, and inventing a tax rate on a
 * priced document would be worse still.
 *
 * Values are plain text. {@code DocxGenerationService} writes them through the
 * OpenXML object model rather than into a string of XML, so no escaping is done
 * or needed here.
 */
public final class ProposalPlaceholders {

    /** "05 September 2026" — unambiguous in every locale, unlike 05/09/2026. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);

    private ProposalPlaceholders() {
    }

    public static Map<String, String> build(Proposal proposal, ProposalAssembly assembly) {
        Deal deal = assembly.deal();
        Account account = assembly.account();
        Contact contact = assembly.contact();
        User owner = assembly.owner();

        Map<String, String> values = new LinkedHashMap<>();

        // --- The proposal itself ---
        values.put("proposalId", nullToEmpty(proposal.getProposalNumber()));
        values.put("proposalNumber", nullToEmpty(proposal.getProposalNumber()));
        values.put("proposalDate", DATE.format(LocalDate.now()));
        values.put("proposalType", proposal.getProposalType().name());
        values.put("proposalTypeName", proposal.getProposalType().displayName());

        // --- Opportunity ---
        values.put("opportunityId", nullToEmpty(deal.getOpportunityId()));
        values.put("dealName", nullToEmpty(deal.getName()));
        // The engagement's name is the closest thing the CRM has to a scope
        // description; the services template prints it as the scope heading.
        values.put("serviceName", nullToEmpty(deal.getName()));
        values.put("productName", assembly.lineItems().isEmpty()
                ? "" : assembly.lineItems().get(0).description());

        // --- Customer ---
        values.put("companyName", nullToEmpty(account.getName()));
        values.put("companyAddress", nullToEmpty(account.getBillingAddress()));
        values.put("industry", nullToEmpty(account.getIndustry()));
        values.put("customerName", nullToEmpty(contact.getFullName()));
        values.put("customerTitle", nullToEmpty(contact.getJobTitle()));
        values.put("customerEmail", nullToEmpty(contact.getEmail()));
        values.put("customerPhone", nullToEmpty(contact.getPhone()));

        // --- Supplier (this CRM's own tenant) ---
        values.put("providerName", nullToEmpty(assembly.providerName()));
        values.put("salesExecutive", nullToEmpty(owner.getFullName()));
        values.put("salesExecutiveEmail", nullToEmpty(owner.getEmail()));
        values.put("salesExecutivePhone", nullToEmpty(owner.getPhone()));

        // --- Commercials ---
        values.put("currency", nullToEmpty(assembly.currency()));
        values.put("totalAmount", formatAmount(assembly.totalAmount()));
        values.put("totalAmountWithCurrency",
                nullToEmpty(assembly.currency()) + " " + formatAmount(assembly.totalAmount()));
        values.put("validUntil", formatDate(assembly.validUntil()));
        values.put("validityDays", String.valueOf(assembly.validityDays()));
        values.put("paymentTerms", nullToEmpty(assembly.paymentTerms()));
        values.put("deliveryTimeline", nullToEmpty(assembly.deliveryTimeline()));

        return values;
    }

    /**
     * Thousands-grouped, always two decimals. A fresh {@link DecimalFormat} per
     * call because the class is not thread-safe and generation runs on request
     * threads.
     */
    public static String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return "";
        }
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        return format.format(amount);
    }

    public static String formatDate(LocalDate date) {
        return date == null ? "" : DATE.format(date);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
