package com.techcrm.crm.marketing.leads.rules;

public enum RuleOperator {
    GT(">"),
    GTE(">="),
    LT("<"),
    LTE("<="),
    EQ("="),
    NEQ("!="),
    IN("is one of"),
    NOT_IN("is not one of"),
    CONTAINS("contains");

    private final String symbol;

    RuleOperator(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}
