package com.techcrm.crm.marketing.email;

import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.marketing.api.MarketingDtos.ContentConfig;
import com.techcrm.crm.marketing.api.MarketingDtos.EmailPreview;
import com.techcrm.crm.marketing.campaign.MarketingCampaign;
import com.techcrm.crm.marketing.common.MarketingJson;
import com.techcrm.crm.marketing.execution.CampaignMessage;
import com.techcrm.crm.marketing.execution.DeliveryProvider.OutgoingEmail;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a campaign email for one recipient from the approved fields. The
 * reviewer's preview and the dispatcher both go through here, so the preview
 * is the email that is sent.
 */
@Component
public class CampaignEmailComposer {

    private static final EmailRenderer.Recipient SAMPLE = new EmailRenderer.Recipient("priya.sharma@example.com", "Priya", "Acme Industries");

    private final EmailSettings settings;
    private final EmailLinks links;

    public CampaignEmailComposer(EmailSettings settings, EmailLinks links) {
        this.settings = settings;
        this.links = links;
    }

    public static ContentConfig config(MarketingCampaign c) {
        return MarketingJson.read(c.getContentConfigJson(), ContentConfig.class, ContentConfig.defaults());
    }

    /** The campaign's From address: its own when set, otherwise the configured default. */
    public String senderAddress(MarketingCampaign c) {
        return settings.senderFor(config(c).senderEmail());
    }

    public static String senderName(MarketingCampaign c) {
        String n = config(c).senderName();
        return n == null || n.isBlank() ? null : n.trim();
    }

    public static String replyTo(MarketingCampaign c) {
        return EmailSettings.normalise(config(c).replyToEmail());
    }

    /** Problems that block sending this campaign's email, in the Marketing Head's terms. */
    public List<String> senderProblems(MarketingCampaign c) {
        List<String> out = new ArrayList<>();
        if (senderName(c) == null) out.add("Add a sender name in Email configuration");
        String from = senderAddress(c);
        if (from == null) {
            out.add("Add a sender email in Email configuration");
        } else if (!settings.isVerifiedSender(from)) {
            out.add("The sender " + from + " isn't a verified sender for the configured email provider");
        }
        return out;
    }

    public EmailRenderer.Context context(MarketingCampaign c, Long messageId) {
        ContentConfig cfg = config(c);
        return new EmailRenderer.Context(senderName(c), c.getProductName(), c.getName(), c.getLandingPageUrl(),
                settings.postalAddress(),
                messageId == null ? null : links.clickUrl(messageId),
                messageId == null ? null : links.openPixelUrl(messageId),
                messageId == null ? null : links.unsubscribeUrl(messageId),
                cfg.personalizationEnabled());
    }

    public OutgoingEmail compose(MarketingCampaign c, Map<String, String> fields, CampaignMessage m, Lead lead, String to) {
        EmailRenderer.Recipient r = EmailRenderer.Recipient.of(lead);
        EmailRenderer.Rendered email = EmailRenderer.render(fields, r, context(c, m.getId()));
        return new OutgoingEmail(m.getId(), senderName(c), senderAddress(c), replyTo(c), to,
                email.subject(), email.html(), email.text(), links.unsubscribeUrl(m.getId()));
    }

    /** With lead null, fills merge fields with sample values. Tracking links are never live in a preview. */
    public EmailPreview preview(MarketingCampaign c, Map<String, String> fields, Lead lead) {
        EmailRenderer.Recipient r = lead == null ? SAMPLE : EmailRenderer.Recipient.of(lead);
        EmailRenderer.Context ctx = context(c, null);
        EmailRenderer.Rendered email = EmailRenderer.render(fields, r, ctx);
        List<String> notes = new ArrayList<>(email.notes());
        if (settings.trackingActive()) notes.add("When sent, the CTA link and an open-tracking image are routed through this CRM to measure clicks and (estimated) opens");
        String name = senderName(c);
        String from = senderAddress(c);
        return new EmailPreview(
                (name == null ? "(sender name not set)" : name) + " <" + (from == null ? "sender not set" : from) + ">",
                replyTo(c), r.email() == null ? "(no email address)" : r.email(),
                email.subject(), email.preheader(), email.html(), email.text(), lead == null, notes);
    }
}
