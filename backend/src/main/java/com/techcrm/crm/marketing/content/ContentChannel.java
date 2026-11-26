package com.techcrm.crm.marketing.content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Content channels and the fields each carries with their maximum lengths.
 * This table is the schema the LLM output is validated against: a key missing
 * here is dropped, an overlong value rejected.
 *
 * Marketing Automation is email-only: EMAIL is the only channel content is
 * generated, reviewed or sent for. The other constants exist solely so rows
 * written before that change (mkt_content_variants.channel) still load; see
 * {@link #isLegacy()}. Nothing creates them any more.
 */
public enum ContentChannel {
    EMAIL(emailFields()),

    /* ---- Legacy, read-only: campaign copy and the non-email channels ---- */
    CORE(Map.of("headline", 90, "promotionalMessage", 600, "shortCopy", 200, "cta", 40)),
    POSTER(Map.of("headline", 70, "subheadline", 140, "body", 400, "cta", 40)),
    SMS(Map.of("text", 320)),
    NOTIFICATION(Map.of("title", 65, "body", 180)),
    SOCIAL(Map.of("post", 1200, "hashtags", 200));

    private final Map<String, Integer> fieldLimits;

    ContentChannel(Map<String, Integer> fieldLimits) {
        this.fieldLimits = fieldLimits;
    }

    /**
     * Email fields in reading order. The model writes subject, preheader,
     * greeting, body, offerDetails, cta and signOff; ctaUrl defaults to the
     * campaign's landing page and is normally left to the Marketing Head.
     */
    private static Map<String, Integer> emailFields() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("subject", 120);
        m.put("preheader", 150);
        m.put("greeting", 120);
        m.put("body", 4000);
        m.put("offerDetails", 800);
        m.put("cta", 40);
        m.put("ctaUrl", 500);
        m.put("signOff", 300);
        return m;
    }

    public Map<String, Integer> fieldLimits() {
        return fieldLimits;
    }

    /** True for channels kept only so pre-email-only rows remain readable. */
    public boolean isLegacy() {
        return this != EMAIL;
    }

    /** Fields that must be present and non-blank for the content to be usable. */
    public List<String> requiredFields() {
        return switch (this) {
            case EMAIL -> List.of("subject", "body", "cta");
            case CORE -> List.of("headline", "promotionalMessage", "cta");
            case POSTER -> List.of("headline", "body", "cta");
            case SMS -> List.of("text");
            case NOTIFICATION -> List.of("title", "body");
            case SOCIAL -> List.of("post");
        };
    }

    /** The JSON key the LLM uses for this channel in its reply. */
    public String jsonKey() {
        return name().toLowerCase();
    }
}
