package com.techcrm.crm.marketing.leads.rules;

import com.techcrm.crm.marketing.api.MarketingDtos.Criterion;
import com.techcrm.crm.marketing.api.MarketingDtos.RuleResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Evaluates the Marketing Head's eligibility rules against one lead,
 * deterministically. Used three ways:
 * <ol>
 *   <li>as facts in the LLM prompt, so the model reasons from the same checks
 *       the system can verify;</li>
 *   <li>as a guard on the model's answer: a lead that definitively fails a
 *       required rule cannot come back "eligible";</li>
 *   <li>as the fallback evaluator when the model is unavailable.</li>
 * </ol>
 *
 * Results are tri-state. UNKNOWN means the lead lacks the data, or its value is
 * a band that straddles the threshold (51-200 against "&gt; 100"). It is never
 * silently counted as a pass or a fail.
 */
public final class RuleEngine {

    public enum Outcome { PASS, FAIL, UNKNOWN }

    public record Evaluation(List<RuleResult> results, Outcome combined, String match) {

        public List<RuleResult> failed() {
            return results.stream().filter(r -> Outcome.FAIL.name().equals(r.outcome())).toList();
        }

        public long count(Outcome outcome) {
            return results.stream().filter(r -> outcome.name().equals(r.outcome())).count();
        }
    }

    private RuleEngine() {
    }

    /** Throws IllegalArgumentException naming the first invalid rule. */
    public static void validate(List<Criterion> criteria) {
        if (criteria == null) return;
        for (Criterion c : criteria) {
            RuleField field = parseField(c.field());
            RuleOperator op = parseOperator(c.operator());
            if (!field.operators().contains(op)) {
                throw new IllegalArgumentException("Operator '" + op.symbol() + "' can't be used with " + field.label());
            }
            String value = c.value() == null ? "" : c.value().trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Rule for " + field.label() + " needs a value");
            }
            if (field.kind() == RuleField.Kind.NUMBER && parseNumber(value) == null) {
                throw new IllegalArgumentException(field.label() + " needs a number, got '" + value + "'");
            }
            if (field.kind() == RuleField.Kind.LEVEL && levelRank(value) < 0) {
                throw new IllegalArgumentException(field.label() + " must be High, Medium or Low");
            }
        }
    }

    public static Evaluation evaluate(List<Criterion> criteria, String match, LeadFacts facts) {
        String mode = "ANY".equalsIgnoreCase(match) ? "ANY" : "ALL";
        List<RuleResult> results = new ArrayList<>();
        if (criteria != null) {
            for (Criterion c : criteria) {
                results.add(evaluateOne(c, facts));
            }
        }
        return new Evaluation(results, combine(results, mode), mode);
    }

    static Outcome combine(List<RuleResult> results, String mode) {
        if (results.isEmpty()) return Outcome.PASS;
        boolean anyPass = false;
        boolean anyFail = false;
        boolean anyUnknown = false;
        for (RuleResult r : results) {
            switch (Outcome.valueOf(r.outcome())) {
                case PASS -> anyPass = true;
                case FAIL -> anyFail = true;
                case UNKNOWN -> anyUnknown = true;
            }
        }
        if ("ANY".equals(mode)) {
            if (anyPass) return Outcome.PASS;
            return anyUnknown ? Outcome.UNKNOWN : Outcome.FAIL;
        }
        if (anyFail) return Outcome.FAIL;
        return anyUnknown ? Outcome.UNKNOWN : Outcome.PASS;
    }

    public static String describe(Criterion c) {
        RuleField field = parseField(c.field());
        RuleOperator op = parseOperator(c.operator());
        return field.label() + " " + op.symbol() + " " + c.value().trim();
    }

    private static RuleResult evaluateOne(Criterion c, LeadFacts f) {
        RuleField field = parseField(c.field());
        RuleOperator op = parseOperator(c.operator());
        String expected = c.value().trim();
        String label = describe(c);

        return switch (field) {
            case COMPANY_SIZE -> range(field, op, expected, label, f.companySize(), f.companySizeMin(), f.companySizeMax());
            case PURCHASE_TIMELINE_DAYS -> range(field, op, expected, label, f.purchaseTimeline(), f.timelineDaysMin(), f.timelineDaysMax());
            case ESTIMATED_DEAL_VALUE -> exact(field, op, expected, label,
                    f.estimatedDealValue() == null ? null : f.estimatedDealValue().toPlainString(), f.estimatedDealValue());
            case LEAD_SCORE -> exact(field, op, expected, label,
                    f.crmLeadScore() == null ? null : String.valueOf(f.crmLeadScore()),
                    f.crmLeadScore() == null ? null : BigDecimal.valueOf(f.crmLeadScore()));
            case PRODUCT_QUANTITY -> exact(field, op, expected, label,
                    f.productQuantity() == null ? null : String.valueOf(f.productQuantity()),
                    f.productQuantity() == null ? null : BigDecimal.valueOf(f.productQuantity()));
            case INTEREST_LEVEL -> level(field, op, expected, label, f.interestLevel());
            case INDUSTRY -> text(field, op, expected, label, f.industry());
            case LOCATION -> text(field, op, expected, label, f.location());
            case CUSTOMER_TYPE -> text(field, op, expected, label, f.customerType());
            case LEAD_SOURCE -> text(field, op, expected, label, f.leadSource());
            case PRODUCT_INTEREST -> text(field, op, expected, label, f.productInterest());
            case QUALIFICATION_STATUS -> text(field, op, expected, label, f.qualificationStatus());
        };
    }

    /** A value known only as a band [min, max]; max null means unbounded. */
    private static RuleResult range(RuleField field, RuleOperator op, String expected, String label,
                                    String actual, Long min, Long max) {
        if (min == null) return result(field, op, expected, actual, Outcome.UNKNOWN, label);
        double x = parseNumber(expected).doubleValue();
        double lo = min;
        Double hi = max == null ? null : (double) max;
        Outcome outcome = switch (op) {
            case GT -> lo > x ? Outcome.PASS : (hi != null && hi <= x ? Outcome.FAIL : Outcome.UNKNOWN);
            case GTE -> lo >= x ? Outcome.PASS : (hi != null && hi < x ? Outcome.FAIL : Outcome.UNKNOWN);
            case LT -> hi != null && hi < x ? Outcome.PASS : (lo >= x ? Outcome.FAIL : Outcome.UNKNOWN);
            case LTE -> hi != null && hi <= x ? Outcome.PASS : (lo > x ? Outcome.FAIL : Outcome.UNKNOWN);
            case EQ -> hi != null && lo == x && hi == x ? Outcome.PASS
                    : (x < lo || (hi != null && x > hi) ? Outcome.FAIL : Outcome.UNKNOWN);
            case NEQ -> hi != null && lo == x && hi == x ? Outcome.FAIL
                    : (x < lo || (hi != null && x > hi) ? Outcome.PASS : Outcome.UNKNOWN);
            default -> Outcome.UNKNOWN;
        };
        return result(field, op, expected, actual, outcome, label);
    }

    private static RuleResult exact(RuleField field, RuleOperator op, String expected, String label,
                                    String actual, BigDecimal value) {
        if (value == null) return result(field, op, expected, null, Outcome.UNKNOWN, label);
        int cmp = value.compareTo(parseNumber(expected));
        boolean pass = switch (op) {
            case GT -> cmp > 0;
            case GTE -> cmp >= 0;
            case LT -> cmp < 0;
            case LTE -> cmp <= 0;
            case EQ -> cmp == 0;
            case NEQ -> cmp != 0;
            default -> false;
        };
        return result(field, op, expected, actual, pass ? Outcome.PASS : Outcome.FAIL, label);
    }

    private static RuleResult level(RuleField field, RuleOperator op, String expected, String label, String actual) {
        int have = levelRank(actual);
        if (have < 0) return result(field, op, expected, actual, Outcome.UNKNOWN, label);
        return exact(field, op, String.valueOf(levelRank(expected)), label, actual, BigDecimal.valueOf(have))
                .withExpected(expected);
    }

    private static RuleResult text(RuleField field, RuleOperator op, String expected, String label, String actual) {
        if (actual == null) return result(field, op, expected, null, Outcome.UNKNOWN, label);
        String have = norm(actual);
        Set<String> options = Arrays.stream(expected.split(","))
                .map(RuleEngine::norm).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        boolean pass = switch (op) {
            case EQ -> have.equals(norm(expected));
            case NEQ -> !have.equals(norm(expected));
            case IN -> options.contains(have);
            case NOT_IN -> !options.contains(have);
            case CONTAINS -> have.contains(norm(expected));
            default -> false;
        };
        return result(field, op, expected, actual, pass ? Outcome.PASS : Outcome.FAIL, label);
    }

    private static RuleResult result(RuleField field, RuleOperator op, String expected, String actual,
                                     Outcome outcome, String label) {
        return new RuleResult(field.name(), op.name(), expected, actual, outcome.name(), label);
    }

    static int levelRank(String level) {
        if (level == null) return -1;
        for (int i = 0; i < RuleField.LEVELS.size(); i++) {
            if (RuleField.LEVELS.get(i).equalsIgnoreCase(level.trim())) return i;
        }
        return -1;
    }

    static BigDecimal parseNumber(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replaceAll("[₹,\\s]", "").replaceAll("(?i)^(rs\\.?|inr)", "");
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static RuleField parseField(String raw) {
        try {
            return RuleField.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unknown rule field: " + raw);
        }
    }

    static RuleOperator parseOperator(String raw) {
        try {
            return RuleOperator.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unknown rule operator: " + raw);
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
