package com.techcrm.crm.marketing.leads.rules;

import java.util.List;

/**
 * The lead attributes a Section E rule can test. Each field has a kind that
 * fixes which operators make sense for it; RuleEngine rejects any other pair
 * when the campaign is saved, so an unevaluable rule never reaches a run.
 */
public enum RuleField {
    COMPANY_SIZE("Company size (employees)", Kind.NUMBER, "Band values like 51-200 are compared as ranges"),
    INDUSTRY("Industry", Kind.TEXT, null),
    LOCATION("Location", Kind.TEXT, null),
    CUSTOMER_TYPE("Customer type", Kind.TEXT, null),
    LEAD_SOURCE("Lead source", Kind.TEXT, null),
    PRODUCT_INTEREST("Product of interest", Kind.TEXT, null),
    INTEREST_LEVEL("Product interest level", Kind.LEVEL, "High / Medium / Low, from the lead's Hot / Warm / Cold rating"),
    ESTIMATED_DEAL_VALUE("Estimated deal value (₹)", Kind.NUMBER, null),
    PURCHASE_TIMELINE_DAYS("Purchase timeline (days)", Kind.NUMBER, "Within 3 Months = up to 90 days"),
    LEAD_SCORE("CRM lead score", Kind.NUMBER, "0-100"),
    PRODUCT_QUANTITY("Product quantity", Kind.NUMBER, null),
    QUALIFICATION_STATUS("Qualification status", Kind.TEXT, "PENDING / QUALIFIED / UNQUALIFIED");

    public enum Kind { NUMBER, TEXT, LEVEL }

    public static final List<String> LEVELS = List.of("Low", "Medium", "High");

    private final String label;
    private final Kind kind;
    private final String hint;

    RuleField(String label, Kind kind, String hint) {
        this.label = label;
        this.kind = kind;
        this.hint = hint;
    }

    public String label() {
        return label;
    }

    public Kind kind() {
        return kind;
    }

    public String hint() {
        return hint;
    }

    public List<RuleOperator> operators() {
        return switch (kind) {
            case NUMBER, LEVEL -> List.of(RuleOperator.GT, RuleOperator.GTE, RuleOperator.LT, RuleOperator.LTE,
                    RuleOperator.EQ, RuleOperator.NEQ);
            case TEXT -> List.of(RuleOperator.EQ, RuleOperator.NEQ, RuleOperator.IN, RuleOperator.NOT_IN,
                    RuleOperator.CONTAINS);
        };
    }
}
