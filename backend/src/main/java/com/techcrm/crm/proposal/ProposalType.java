package com.techcrm.crm.proposal;

/**
 * The proposal templates this CRM can produce.
 *
 * Three, chosen the same way the contract templates were: by what the CRM
 * actually holds enough data to fill. A deal that names a product gets a priced
 * quotation; a large one gets the implementation proposal, which has room for
 * scope and timeline; a deal with no product at all gets the services proposal,
 * where a unit-price column would be meaningless.
 *
 * A fourth would have to invent its own inputs, and a placeholder that always
 * renders empty reads worse to a customer than a section that isn't there.
 */
public enum ProposalType {

    /** A priced quotation for a named product at a unit price. */
    STANDARD_SALES_PROPOSAL("Standard Sales Proposal", "standard-sales-proposal.docx"),

    /** A larger engagement: the product plus the work of putting it in. Selected
     *  for high-value deals. */
    SOFTWARE_IMPLEMENTATION_PROPOSAL("Software Implementation Proposal", "software-implementation-proposal.docx"),

    /** Consulting or delivery work, where nothing discrete is shipped. */
    PROFESSIONAL_SERVICES_PROPOSAL("Professional Services Proposal", "professional-services-proposal.docx");

    private final String displayName;
    private final String defaultTemplateFile;

    ProposalType(String displayName, String defaultTemplateFile) {
        this.displayName = displayName;
        this.defaultTemplateFile = defaultTemplateFile;
    }

    public String displayName() {
        return displayName;
    }

    /** File name under the template directory, unless overridden in configuration. */
    public String defaultTemplateFile() {
        return defaultTemplateFile;
    }
}
