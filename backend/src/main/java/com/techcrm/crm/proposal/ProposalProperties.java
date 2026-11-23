package com.techcrm.crm.proposal;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything about proposal generation that differs between environments.
 *
 * Deliberately its own {@code @ConfigurationProperties} rather than more fields
 * on {@link com.techcrm.crm.contract.ContractProperties}: the two documents are
 * generated at different points in the pipeline and an administrator changing
 * when proposals are produced should not have to reason about whether they have
 * also changed when contracts are.
 *
 * The one thing it does <em>not</em> restate is document storage. Proposals write
 * through the existing {@code DocumentStorageService}, so they land under
 * {@code contract.storage.root} with everything else. A second root would mean a
 * second thing to back up and a second thing to get wrong.
 */
@Component
@ConfigurationProperties(prefix = "proposal")
@Getter
@Setter
public class ProposalProperties {

    /**
     * Deal stages a proposal may be generated from.
     *
     * Just PROPOSAL by default, and narrower than the contract module's list on
     * purpose. The trigger for this bot is the deal reaching the Proposal stage;
     * "Proposal Accepted" is a later business outcome and is what the contract
     * bot keys off. Generating a proposal for a deal already in NEGOTIATION
     * would put a fresh quotation in front of a customer who is arguing about
     * the last one.
     *
     * Configurable because a team that works its pipeline differently should not
     * need a code change.
     */
    private List<String> eligibleStages = List.of("PROPOSAL");

    /**
     * Render off the request thread. On by default, for the same reason the
     * contract module does it: SAP Build Process Automation abandons an HTTP
     * call at 30 seconds and a cold generation takes longer than that.
     *
     * false renders inline and answers 201 with the documents attached --
     * slower to respond, but a render failure comes back as a 500 on the call
     * that caused it rather than as a status somebody has to go looking for.
     */
    private boolean asyncGeneration = true;

    /**
     * How long a quotation stands, when the caller supplies no expiry.
     *
     * Thirty days is the common commercial default, and it is the reason
     * proposals carry a validity rather than a term: after this the pricing is
     * no longer ours to honour.
     */
    private int defaultValidityDays = 30;

    /** Neither is recorded anywhere in the CRM, so they default from here and
     *  can be overridden per request. */
    private String defaultPaymentTerms =
            "50% on acceptance of this proposal, 50% on delivery. Invoices are payable within 30 days.";
    private String defaultDeliveryTimeline =
            "Within 30 days of proposal acceptance.";

    /** At or above this deal value, a product sale is proposed as an
     *  implementation engagement instead of a straight quotation. In the deal's
     *  own currency — the CRM does not convert, and defaults to INR. */
    private BigDecimal implementationValueThreshold = new BigDecimal("2500000");

    private Templates templates = new Templates();

    @Getter
    @Setter
    public static class Templates {
        /** Where the .docx templates live. A {@code classpath:} location uses the
         *  bundled set; a filesystem location lets an admin drop in their own
         *  without a rebuild. */
        private String location = "classpath:proposal-templates/";

        /** Per-type overrides, keyed by {@link ProposalType} name. A value here
         *  replaces the type's default file name, and may be an absolute path or
         *  any Spring resource location. */
        private Map<String, String> overrides = new LinkedHashMap<>();
    }
}
