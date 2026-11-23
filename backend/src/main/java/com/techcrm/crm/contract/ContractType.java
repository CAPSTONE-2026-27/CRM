package com.techcrm.crm.contract;

/**
 * The contract templates this CRM can produce.
 *
 * Three, because three are what the CRM actually holds enough data to fill:
 * a unit-priced product sale (leads.product + product_quantity), a term
 * subscription for larger accounts, and a services engagement for deals that
 * carry no product at all. A fourth would have to invent its own inputs.
 */
public enum ContractType {

    /** A one-off supply of a named product at a unit price. */
    STANDARD_SALES_AGREEMENT("Standard Sales Agreement", "standard-sales-agreement.docx"),

    /** A recurring, term-bounded licence. Selected for high-value deals. */
    ENTERPRISE_SUBSCRIPTION_AGREEMENT("Enterprise Subscription Agreement", "enterprise-subscription-agreement.docx"),

    /** Time-and-materials or fixed-fee services, where nothing discrete is shipped. */
    PROFESSIONAL_SERVICES_AGREEMENT("Professional Services Agreement", "professional-services-agreement.docx");

    private final String displayName;
    private final String defaultTemplateFile;

    ContractType(String displayName, String defaultTemplateFile) {
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
