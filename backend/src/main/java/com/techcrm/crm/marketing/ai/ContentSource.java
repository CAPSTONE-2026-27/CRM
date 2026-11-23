package com.techcrm.crm.marketing.ai;

import com.techcrm.crm.marketing.api.MarketingDtos.Audience;
import com.techcrm.crm.marketing.api.MarketingDtos.ContentConfig;
import com.techcrm.crm.marketing.campaign.MarketingCampaign;
import com.techcrm.crm.marketing.common.MarketingJson;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Everything the Marketing Head supplied that content may be built from, and
 * nothing else. The same object feeds the model prompt, the template fallback
 * and the grounding check, so all three agree on what counts as a supplied fact.
 */
public record ContentSource(
        String campaignName,
        String campaignType,
        String objective,
        String description,
        String startDate,
        String endDate,
        String productName,
        String productDescription,
        String mainBenefits,
        String offer,
        String discount,
        String pricingInfo,
        String sellingPoints,
        String callToAction,
        String landingPageUrl,
        String senderName,
        String targetAudience,
        String customerSegment,
        String tone,
        String language,
        String writingStyle,
        String additionalInstructions,
        boolean personalization
) {

    public static ContentSource from(MarketingCampaign c) {
        Audience a = MarketingJson.read(c.getAudienceJson(), Audience.class, Audience.empty());
        ContentConfig cfg = MarketingJson.read(c.getContentConfigJson(), ContentConfig.class, ContentConfig.defaults());
        return new ContentSource(
                clean(c.getName(), 160),
                c.getCampaignType() == null ? null : c.getCampaignType().name().replace('_', ' ').toLowerCase(),
                clean(c.getObjective(), 300),
                clean(c.getDescription(), 3000),
                c.getStartDate() == null ? null : c.getStartDate().toString(),
                c.getEndDate() == null ? null : c.getEndDate().toString(),
                clean(c.getProductName(), 200),
                clean(c.getProductDescription(), 3000),
                clean(c.getMainBenefits(), 2000),
                clean(c.getOffer(), 1500),
                clean(c.getDiscount(), 120),
                clean(c.getPricingInfo(), 1500),
                clean(c.getSellingPoints(), 2000),
                clean(firstNonBlank(cfg.cta(), c.getCallToAction()), 200),
                clean(c.getLandingPageUrl(), 500),
                clean(cfg.senderName(), 120),
                clean(describeAudience(a), 600),
                clean(a.customerSegment(), 200),
                clean(firstNonBlank(cfg.tone(), "Professional"), 60),
                clean(firstNonBlank(cfg.language(), "English"), 40),
                clean(cfg.writingStyle(), 60),
                clean(cfg.additionalInstructions(), 1500),
                cfg.personalizationEnabled());
    }

    /** The "brief" section of the prompt: supplied facts only, blanks omitted. */
    public Map<String, Object> brief() {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "campaign_name", campaignName);
        put(m, "campaign_type", campaignType);
        put(m, "objective", objective);
        put(m, "description", description);
        put(m, "start_date", startDate);
        put(m, "end_date", endDate);
        put(m, "product_name", productName);
        put(m, "product_description", productDescription);
        put(m, "main_benefits", mainBenefits);
        put(m, "offer", offer);
        put(m, "discount", discount);
        put(m, "pricing", pricingInfo);
        put(m, "key_selling_points", sellingPoints);
        put(m, "call_to_action", callToAction);
        put(m, "landing_page_url", landingPageUrl);
        put(m, "sender_name", senderName);
        put(m, "target_audience", targetAudience);
        put(m, "customer_segment", customerSegment);
        return m;
    }

    public Map<String, Object> style() {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "tone", tone);
        put(m, "language", language);
        put(m, "writing_style", writingStyle);
        put(m, "additional_instructions", additionalInstructions);
        if (!personalization) put(m, "personalisation", "Do not use any {{...}} personalisation tokens; write for every recipient alike.");
        return m;
    }

    /**
     * The text content must be grounded in. Style fields are included because a
     * Marketing Head may legitimately put a fact there ("mention the 15 March
     * webinar"); they are still the Marketing Head's own words.
     */
    public String groundingText() {
        return brief().values().stream().map(String::valueOf).collect(Collectors.joining("\n"))
                + "\n" + (additionalInstructions == null ? "" : additionalInstructions);
    }

    /** The minimal brief for lead evaluation. No offer, pricing or product detail: that pipeline doesn't need it. */
    public Map<String, Object> audienceBrief(MarketingCampaign c) {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "campaign_type", campaignType);
        put(m, "objective", objective);
        put(m, "product_name", productName);
        put(m, "target_audience", targetAudience);
        put(m, "customer_segment", customerSegment);
        Audience a = MarketingJson.read(c.getAudienceJson(), Audience.class, Audience.empty());
        put(m, "other_conditions", clean(a.otherConditions(), 600));
        return m;
    }

    static String describeAudience(Audience a) {
        StringBuilder sb = new StringBuilder();
        append(sb, "Industries", join(a.industries()));
        append(sb, "Company size", a.companySize());
        append(sb, "Locations", join(a.locations()));
        append(sb, "Customer types", join(a.customerTypes()));
        append(sb, "Lead sources", join(a.leadSources()));
        append(sb, "Product interest", a.productInterest());
        if (a.minDealValue() != null || a.maxDealValue() != null) {
            append(sb, "Deal value (INR)", (a.minDealValue() == null ? "any" : a.minDealValue().toPlainString())
                    + " to " + (a.maxDealValue() == null ? "any" : a.maxDealValue().toPlainString()));
        }
        append(sb, "Purchase timeline", a.purchaseTimeline());
        if (a.customerStatus() != null && !"ANY".equals(a.customerStatus())) {
            append(sb, "Customer status", "EXISTING_CUSTOMERS".equals(a.customerStatus()) ? "existing customers" : "new leads");
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) return;
        if (!sb.isEmpty()) sb.append("; ");
        sb.append(label).append(": ").append(value.trim());
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(", ", values);
    }

    private static void put(Map<String, Object> m, String key, String value) {
        if (value != null && !value.isBlank()) m.put(key, value);
    }

    private static String clean(String value, int max) {
        return PromptLibrary.clean(value, max);
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
