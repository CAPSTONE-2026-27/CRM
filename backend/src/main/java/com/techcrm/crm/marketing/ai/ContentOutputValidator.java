package com.techcrm.crm.marketing.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.techcrm.crm.marketing.content.ContentChannel;
import com.techcrm.crm.marketing.email.EmailRenderer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates generated content before it is stored or shown.
 *
 * Structure: the channel's required fields are present, no field exceeds its
 * limit, no markup or script.
 *
 * Grounding, the check that matters most: the model may not introduce a
 * figure, link, contact detail or strong commercial claim that the Marketing
 * Head did not supply. Every number in the output must occur in the brief; so
 * must every URL and email address; and "free", "guaranteed", "#1" and similar
 * are allowed only when the brief uses them. These are errors: content that
 * fails is rejected, not shown with a caveat.
 *
 * Softer superlatives ("leading", "best-in-class") are warnings, surfaced to
 * the reviewer on the content card.
 */
public final class ContentOutputValidator {

    public record Result(Map<String, String> fields, List<String> errors, List<String> warnings) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])(\\d[\\d,]*(?:\\.\\d+)?)\\s*(%|percent)?");
    private static final Pattern CURRENCY_BEFORE = Pattern.compile("(₹|rs\\.?|inr|\\$|€|£|usd)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s)\\]]+");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+\\b");
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");
    private static final Pattern BRACKET_PLACEHOLDER = Pattern.compile("\\[(?:your|insert|company|name|first|link|date)[^\\]]{0,40}]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT = Pattern.compile("(?i)<\\s*script|javascript:|on\\w+\\s*=");
    private static final Pattern TAG = Pattern.compile("<[^>]{1,200}>");
    private static final Set<String> ALLOWED_TOKENS = EmailRenderer.MERGE_FIELDS;
    private static final Pattern HTTP_URL = Pattern.compile("(?i)^https?://[^\\s<>\"']+$");

    /** Claims the model may only repeat, never originate. Key: regex; value: what to call it. */
    private static final Map<Pattern, String> CLAIMS = new LinkedHashMap<>();
    static {
        CLAIMS.put(Pattern.compile("(?i)\\bfree\\b"), "free");
        CLAIMS.put(Pattern.compile("(?i)\\bguarantee(?:d|s)?\\b"), "guaranteed");
        CLAIMS.put(Pattern.compile("(?i)\\brisk[- ]free\\b"), "risk-free");
        CLAIMS.put(Pattern.compile("(?i)(?:#\\s?1\\b|\\bnumber one\\b|\\bno\\.\\s?1\\b)"), "#1 / number one");
        CLAIMS.put(Pattern.compile("(?i)\\bcheapest\\b"), "cheapest");
        CLAIMS.put(Pattern.compile("(?i)\\blowest price"), "lowest price");
        CLAIMS.put(Pattern.compile("(?i)\\blimited[- ]time\\b"), "limited time");
        CLAIMS.put(Pattern.compile("(?i)\\bcertified\\b"), "certified");
        CLAIMS.put(Pattern.compile("(?i)\\baward[- ]winning\\b"), "award-winning");
    }

    private static final Map<Pattern, String> SOFT_CLAIMS = new LinkedHashMap<>();
    static {
        SOFT_CLAIMS.put(Pattern.compile("(?i)\\bbest[- ]in[- ]class\\b"), "best-in-class");
        SOFT_CLAIMS.put(Pattern.compile("(?i)\\bindustry[- ]leading\\b"), "industry-leading");
        SOFT_CLAIMS.put(Pattern.compile("(?i)\\bworld[- ]class\\b"), "world-class");
        SOFT_CLAIMS.put(Pattern.compile("(?i)\\brevolutionary\\b"), "revolutionary");
        SOFT_CLAIMS.put(Pattern.compile("(?i)\\bunmatched\\b"), "unmatched");
        SOFT_CLAIMS.put(Pattern.compile("(?i)\\bexclusive\\b"), "exclusive");
    }

    private ContentOutputValidator() {
    }

    /** Validates one channel's object from the model reply. */
    public static Result validate(ContentChannel channel, JsonNode node, String groundingText) {
        Map<String, String> fields = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        if (node == null || !node.isObject()) {
            errors.add(channel.name() + ": missing from the reply");
            return new Result(fields, errors, List.of());
        }
        for (String key : channel.fieldLimits().keySet()) {
            JsonNode value = node.get(key);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                fields.put(key, value.asText().trim());
            }
        }
        return validate(channel, fields, groundingText);
    }

    /** Validates a field map, whether produced by the model or edited by a person. */
    public static Result validate(ContentChannel channel, Map<String, String> input, String groundingText) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> fields = new LinkedHashMap<>();
        String source = groundingText == null ? "" : groundingText.toLowerCase(Locale.ROOT);
        Set<String> sourceNumbers = numbersIn(source);
        String label = channel.name().charAt(0) + channel.name().substring(1).toLowerCase(Locale.ROOT);

        for (Map.Entry<String, String> e : input.entrySet()) {
            Integer limit = channel.fieldLimits().get(e.getKey());
            if (limit == null || e.getValue() == null) continue;
            String value = e.getValue().replace("\r\n", "\n").trim();
            if (value.isEmpty()) continue;

            if (SCRIPT.matcher(value).find()) {
                errors.add(label + " " + e.getKey() + ": contains script or event-handler markup");
                continue;
            }
            if (TAG.matcher(value).find()) {
                value = TAG.matcher(value).replaceAll("").trim();
                warnings.add(label + " " + e.getKey() + ": HTML tags were removed");
            }
            if (value.length() > limit) {
                errors.add(label + " " + e.getKey() + ": " + value.length() + " characters, limit is " + limit);
            }
            if ("ctaUrl".equals(e.getKey()) && !HTTP_URL.matcher(value).matches()) {
                errors.add(label + " ctaUrl: must be an http(s) link");
            }
            fields.put(e.getKey(), value);
        }

        for (String required : channel.requiredFields()) {
            if (!fields.containsKey(required)) errors.add(label + ": '" + required + "' is required");
        }

        String all = String.join("\n", fields.values());
        String lower = all.toLowerCase(Locale.ROOT);

        Matcher numbers = NUMBER.matcher(all);
        Set<String> flagged = new HashSet<>();
        while (numbers.find()) {
            String digits = numbers.group(1).replace(",", "");
            if (digits.endsWith(".")) digits = digits.substring(0, digits.length() - 1);
            boolean percent = numbers.group(2) != null;
            boolean currency = CURRENCY_BEFORE.matcher(all.substring(Math.max(0, numbers.start() - 5), numbers.start())).find();
            boolean trivial = digits.length() == 1 && !percent && !currency;
            if (trivial || sourceNumbers.contains(stripZeros(digits))) continue;
            String shown = numbers.group().trim();
            if (flagged.add(shown)) {
                errors.add(label + ": the figure '" + shown + "' does not appear in the campaign brief");
            }
        }

        checkAll(URL, all, source, label, "link", errors);
        checkAll(EMAIL, all, source, label, "email address", errors);

        Matcher tokens = TOKEN.matcher(all);
        while (tokens.find()) {
            if (!ALLOWED_TOKENS.contains(tokens.group(1).toLowerCase(Locale.ROOT))) {
                errors.add(label + ": unsupported placeholder " + tokens.group());
            }
        }
        Matcher brackets = BRACKET_PLACEHOLDER.matcher(all);
        if (brackets.find()) errors.add(label + ": unfilled placeholder " + brackets.group());

        for (Map.Entry<Pattern, String> claim : CLAIMS.entrySet()) {
            if (claim.getKey().matcher(lower).find() && !claim.getKey().matcher(source).find()) {
                errors.add(label + ": claims '" + claim.getValue() + "', which the campaign brief does not state");
            }
        }
        for (Map.Entry<Pattern, String> claim : SOFT_CLAIMS.entrySet()) {
            if (claim.getKey().matcher(lower).find() && !claim.getKey().matcher(source).find()) {
                warnings.add(label + ": uses '" + claim.getValue() + "'; confirm the claim can be substantiated");
            }
        }
        return new Result(fields, errors, warnings);
    }

    private static void checkAll(Pattern pattern, String text, String source, String label, String what, List<String> errors) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String found = m.group().toLowerCase(Locale.ROOT).replaceAll("[.,;:!?]+$", "");
            if (!source.contains(found)) {
                errors.add(label + ": the " + what + " '" + m.group() + "' does not appear in the campaign brief");
            }
        }
    }

    static Set<String> numbersIn(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String digits = m.group(1).replace(",", "");
            if (digits.endsWith(".")) digits = digits.substring(0, digits.length() - 1);
            out.add(stripZeros(digits));
        }
        return out;
    }

    /** "5000.00" and "5000" are the same figure. */
    private static String stripZeros(String digits) {
        if (!digits.contains(".")) return digits;
        String trimmed = digits.replaceAll("0+$", "");
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
