package com.techcrm.crm.marketing.ai;

import com.techcrm.crm.marketing.content.ContentChannel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic fallback used when the model is unavailable or its email
 * output failed validation. It only rearranges the Marketing Head's own
 * words into each channel's fields, so it cannot introduce a claim, and the
 * result is labelled SYSTEM_GENERATED so nobody mistakes it for AI copy. It is
 * a starting draft for a person to edit, not a substitute for the model.
 */
public final class TemplateContentComposer {

    private static final String DEFAULT_CTA = "Learn more";

    private TemplateContentComposer() {
    }

    public static Map<String, String> compose(ContentChannel channel, ContentSource s) {
        String product = first(s.productName(), s.campaignName());
        String cta = first(s.callToAction(), DEFAULT_CTA);
        String offerLine = s.offer() != null ? s.offer()
                : s.discount() != null ? s.discount() : null;
        String headline = offerLine != null ? product + ": " + firstSentence(offerLine) : product;
        String pitch = first(s.productDescription(), s.description(), s.objective(), product);
        String benefits = s.mainBenefits() != null ? s.mainBenefits() : s.sellingPoints();

        Map<String, String> f = new LinkedHashMap<>();
        // Email is the only channel content is generated for; legacy channels get nothing.
        if (channel != ContentChannel.EMAIL) return f;
        f.put("subject", clip(headline, channel, "subject"));
        if (s.objective() != null) f.put("preheader", clip(s.objective(), channel, "preheader"));
        f.put("greeting", s.personalization() ? "Hello {{first_name}}," : "Hello,");
        StringBuilder body = new StringBuilder(pitch);
        if (benefits != null) body.append("\n\n").append(bullets(benefits));
        f.put("body", clip(body.toString(), channel, "body"));
        String offerDetails = join(offerLine, s.pricingInfo());
        if (!offerDetails.isEmpty()) f.put("offerDetails", clip(offerDetails, channel, "offerDetails"));
        f.put("cta", clip(cta, channel, "cta"));
        if (s.landingPageUrl() != null) f.put("ctaUrl", clip(s.landingPageUrl(), channel, "ctaUrl"));
        if (s.senderName() != null) f.put("signOff", clip("Best regards,\n" + s.senderName(), channel, "signOff"));
        f.values().removeIf(v -> v == null || v.isBlank());
        return f;
    }

    private static String bullets(String text) {
        String[] parts = text.split("\\s*(?:\\n|;|•)\\s*");
        if (parts.length <= 1) return text;
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isBlank()) continue;
            if (!sb.isEmpty()) sb.append('\n');
            sb.append("- ").append(p.replaceFirst("^[-*]\\s*", "").trim());
        }
        return sb.toString();
    }

    private static String firstSentence(String text) {
        if (text == null) return null;
        int end = text.indexOf(". ");
        String line = text.split("\\n", 2)[0];
        String sentence = end > 0 && end < line.length() ? text.substring(0, end + 1) : line;
        return sentence.trim();
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (!sb.isEmpty()) sb.append(sb.charAt(sb.length() - 1) == '.' ? " " : ". ");
            sb.append(p.trim());
        }
        return sb.toString();
    }

    private static String clip(String value, ContentChannel channel, String field) {
        if (value == null) return null;
        int limit = channel.fieldLimits().get(field);
        if (value.length() <= limit) return value;
        int cut = value.lastIndexOf(' ', limit - 1);
        return value.substring(0, cut > limit / 2 ? cut : limit - 1).trim() + "…";
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }
}
