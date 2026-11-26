package com.techcrm.crm.marketing.email;

import com.techcrm.crm.lead.Lead;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns approved email fields into the HTML and plain-text bodies a recipient
 * receives. Used for the reviewer's preview and for sending, so what was
 * approved is exactly what goes out.
 *
 * The model (or the Marketing Head) only ever writes plain text; all markup is
 * produced here and every value is HTML-escaped, so content can't inject
 * markup or script. Personalisation fields resolve per recipient with neutral
 * fallbacks when the CRM has no value.
 */
public final class EmailRenderer {

    /** Merge fields content may use. {{company}} is the older spelling of {{company_name}}. */
    public static final Set<String> MERGE_FIELDS = Set.of("first_name", "company_name", "company", "product_name");

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");
    private static final Pattern HTTP_URL = Pattern.compile("(?i)^https?://[^\\s<>\"']+$");
    private static final String FALLBACK_FIRST_NAME = "there";
    private static final String FALLBACK_COMPANY = "your company";

    private EmailRenderer() {
    }

    public record Recipient(String email, String firstName, String companyName) {

        public static Recipient of(Lead lead) {
            String first = lead.getFullName() == null || lead.getFullName().isBlank() ? null
                    : lead.getFullName().trim().split("\\s+")[0];
            String company = lead.getCompany() == null || lead.getCompany().isBlank() ? null : lead.getCompany().trim();
            return new Recipient(lead.getEmail(), first, company);
        }
    }

    /**
     * Campaign-level values. clickUrl/openPixelUrl are null when tracking is
     * off, unsubscribeUrl when there is no public base URL (preview only;
     * sending requires it).
     */
    public record Context(String senderName, String productName, String campaignName, String defaultCtaUrl,
                          String postalAddress, String clickUrl, String openPixelUrl, String unsubscribeUrl,
                          boolean personalization) {
    }

    public record Rendered(String subject, String preheader, String html, String text, String ctaUrl, List<String> notes) {
    }

    public static Rendered render(Map<String, String> fields, Recipient recipient, Context ctx) {
        Set<String> notes = new LinkedHashSet<>();
        String subject = oneLine(personalise(fields.get("subject"), recipient, ctx, notes));
        String preheader = oneLine(personalise(fields.get("preheader"), recipient, ctx, notes));
        String greeting = personalise(firstNonBlank(fields.get("greeting"),
                ctx.personalization() ? "Hello {{first_name}}," : "Hello,"), recipient, ctx, notes);
        String body = personalise(fields.get("body"), recipient, ctx, notes);
        String offer = personalise(fields.get("offerDetails"), recipient, ctx, notes);
        String cta = oneLine(personalise(fields.get("cta"), recipient, ctx, notes));
        String signOff = personalise(firstNonBlank(fields.get("signOff"),
                "Best regards,\n" + firstNonBlank(ctx.senderName(), "The team")), recipient, ctx, notes);

        String ctaUrl = firstNonBlank(trimmed(fields.get("ctaUrl")), trimmed(ctx.defaultCtaUrl()));
        if (ctaUrl != null && !HTTP_URL.matcher(ctaUrl).matches()) {
            notes.add("The CTA link isn't an http(s) URL, so the call-to-action is shown without a link");
            ctaUrl = null;
        }
        if (ctaUrl == null && cta != null) {
            notes.add("No CTA link or landing page is set; the call-to-action is shown as text");
        }
        String href = ctaUrl == null ? null : firstNonBlank(ctx.clickUrl(), ctaUrl);
        if (ctx.unsubscribeUrl() == null) notes.add("The unsubscribe link is added when the email is sent");

        return new Rendered(subject, preheader,
                html(subject, preheader, greeting, body, offer, cta, href, signOff, ctx),
                text(greeting, body, offer, cta, ctaUrl, signOff, ctx),
                ctaUrl, List.copyOf(notes));
    }

    /** Replaces merge fields for one recipient. Unknown fields are removed (validation normally stops them earlier). */
    public static String personalise(String value, Recipient r, Context ctx, Set<String> notes) {
        if (value == null || value.isBlank()) return null;
        Matcher m = TOKEN.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1).toLowerCase(Locale.ROOT);
            String replacement = switch (key) {
                case "first_name" -> fallback(r.firstName(), FALLBACK_FIRST_NAME, "{{first_name}}", notes);
                case "company_name", "company" -> fallback(r.companyName(), FALLBACK_COMPANY, "{{company_name}}", notes);
                case "product_name" -> fallback(ctx.productName(), firstNonBlank(ctx.campaignName(), "our product"),
                        "{{product_name}}", notes);
                default -> {
                    notes.add("Unsupported field " + m.group() + " was removed");
                    yield "";
                }
            };
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString().trim();
    }

    private static String fallback(String value, String fallback, String token, Set<String> notes) {
        if (value != null && !value.isBlank()) return value.trim();
        notes.add(token + " has no value for this recipient and becomes \"" + fallback + "\"");
        return fallback;
    }

    /* ================================================================
     * HTML
     * ================================================================ */

    private static String html(String subject, String preheader, String greeting, String body, String offer, String cta,
                               String href, String signOff, Context ctx) {
        StringBuilder h = new StringBuilder(4096);
        h.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(esc(subject)).append("</title></head>")
                .append("<body style=\"margin:0;padding:0;background:#f4f5f7;\">");
        if (preheader != null) {
            h.append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;\">").append(esc(preheader)).append("</div>");
        }
        h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f4f5f7;\"><tr><td align=\"center\" style=\"padding:24px 12px;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:600px;background:#ffffff;border-radius:8px;\">")
                .append("<tr><td style=\"padding:28px 32px;font-family:Arial,Helvetica,sans-serif;font-size:15px;line-height:1.6;color:#1f2328;\">");
        if (greeting != null) h.append("<p style=\"margin:0 0 16px;\">").append(multiline(greeting)).append("</p>");
        if (body != null) h.append(blocks(body));
        if (offer != null) {
            h.append("<div style=\"margin:20px 0;padding:14px 16px;background:#f1f6fc;border-left:4px solid #185fa5;\">")
                    .append(blocks(offer)).append("</div>");
        }
        if (cta != null) {
            if (href != null) {
                h.append("<p style=\"margin:24px 0;\"><a href=\"").append(esc(href))
                        .append("\" style=\"display:inline-block;background:#185fa5;color:#ffffff;text-decoration:none;padding:12px 22px;border-radius:6px;font-weight:bold;\">")
                        .append(esc(cta)).append("</a></p>");
            } else {
                h.append("<p style=\"margin:24px 0;font-weight:bold;\">").append(esc(cta)).append("</p>");
            }
        }
        if (signOff != null) h.append("<p style=\"margin:24px 0 0;\">").append(multiline(signOff)).append("</p>");
        h.append("</td></tr></table>");

        h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:600px;\">")
                .append("<tr><td style=\"padding:16px 32px;font-family:Arial,Helvetica,sans-serif;font-size:12px;line-height:1.5;color:#6b6f76;text-align:center;\">");
        if (ctx.senderName() != null) h.append("You received this email from ").append(esc(ctx.senderName())).append(".<br>");
        if (ctx.postalAddress() != null) h.append(esc(ctx.postalAddress())).append("<br>");
        if (ctx.unsubscribeUrl() != null) {
            h.append("<a href=\"").append(esc(ctx.unsubscribeUrl())).append("\" style=\"color:#6b6f76;\">Unsubscribe</a> from these emails.");
        }
        h.append("</td></tr></table></td></tr></table>");
        if (ctx.openPixelUrl() != null) {
            h.append("<img src=\"").append(esc(ctx.openPixelUrl())).append("\" width=\"1\" height=\"1\" alt=\"\" style=\"display:block;border:0;\">");
        }
        return h.append("</body></html>").toString();
    }

    /** Paragraphs on blank lines; runs of "- " or "• " lines become a list; single newlines become <br>. */
    static String blocks(String text) {
        StringBuilder out = new StringBuilder();
        for (String para : text.replace("\r\n", "\n").split("\\n\\s*\\n")) {
            String[] lines = para.strip().split("\\n");
            if (lines.length == 0 || para.isBlank()) continue;
            boolean list = true;
            for (String l : lines) list &= l.strip().matches("^[-•*]\\s+.+");
            if (list) {
                out.append("<ul style=\"margin:0 0 16px;padding-left:20px;\">");
                for (String l : lines) out.append("<li>").append(esc(l.strip().replaceFirst("^[-•*]\\s+", ""))).append("</li>");
                out.append("</ul>");
            } else {
                out.append("<p style=\"margin:0 0 16px;\">").append(multiline(para.strip())).append("</p>");
            }
        }
        return out.toString();
    }

    private static String multiline(String s) {
        return esc(s).replace("\n", "<br>");
    }

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /* ================================================================
     * Plain text
     * ================================================================ */

    private static String text(String greeting, String body, String offer, String cta, String ctaUrl, String signOff,
                               Context ctx) {
        List<String> parts = new ArrayList<>();
        if (greeting != null) parts.add(greeting);
        if (body != null) parts.add(body);
        if (offer != null) parts.add(offer);
        if (cta != null) parts.add(ctaUrl != null ? cta + ": " + firstNonBlank(ctx.clickUrl(), ctaUrl) : cta);
        if (signOff != null) parts.add(signOff);
        StringBuilder footer = new StringBuilder("--\n");
        if (ctx.senderName() != null) footer.append("You received this email from ").append(ctx.senderName()).append(".\n");
        if (ctx.postalAddress() != null) footer.append(ctx.postalAddress()).append('\n');
        if (ctx.unsubscribeUrl() != null) footer.append("Unsubscribe: ").append(ctx.unsubscribeUrl()).append('\n');
        parts.add(footer.toString().trim());
        return String.join("\n\n", parts) + "\n";
    }

    /* ================================================================ */

    private static String oneLine(String s) {
        return s == null ? null : s.replaceAll("\\s*\\R\\s*", " ").trim();
    }

    private static String trimmed(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
