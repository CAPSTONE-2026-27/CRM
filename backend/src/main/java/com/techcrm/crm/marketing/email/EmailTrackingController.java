package com.techcrm.crm.marketing.email;

import com.techcrm.crm.marketing.api.MarketingDtos.DeliveryEventsRequest;
import com.techcrm.crm.marketing.campaign.MarketingCampaign;
import com.techcrm.crm.marketing.campaign.MarketingCampaignRepository;
import com.techcrm.crm.marketing.common.MarketingJson;
import com.techcrm.crm.marketing.content.ContentChannel;
import com.techcrm.crm.marketing.content.ContentVariantRepository;
import com.techcrm.crm.marketing.email.EmailLinks.Purpose;
import com.techcrm.crm.marketing.execution.CampaignExecutionService;
import com.techcrm.crm.marketing.execution.CampaignMessage;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

/**
 * The endpoints a campaign email's recipient (or the email provider) reaches,
 * so they are public: /api/marketing/public/** is permitted without a CRM
 * login in SecurityConfig. Every link is authenticated by its HMAC token
 * (EmailLinks), and the webhook by a shared secret.
 *
 * Nothing here reveals campaign or lead data. The click redirect only ever
 * goes to the approved email's own CTA link, never to a URL from the request,
 * so it can't be used as an open redirect.
 */
@RestController
@RequestMapping(EmailLinks.PUBLIC_PATH)
public class EmailTrackingController {

    private static final byte[] PIXEL = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");

    private final EmailLinks links;
    private final EmailSettings settings;
    private final CampaignExecutionService execution;
    private final MarketingCampaignRepository campaigns;
    private final ContentVariantRepository contents;

    public EmailTrackingController(EmailLinks links, EmailSettings settings, CampaignExecutionService execution,
                                   MarketingCampaignRepository campaigns, ContentVariantRepository contents) {
        this.links = links;
        this.settings = settings;
        this.execution = execution;
        this.campaigns = campaigns;
        this.contents = contents;
    }

    /** Open tracking image. Counted as an estimate: privacy proxies and scanners load images too. */
    @GetMapping("/o/{token}.gif")
    public ResponseEntity<byte[]> open(@PathVariable String token) {
        Long id = links.verify(Purpose.OPEN, token);
        if (id != null) execution.recordTracked(id, "OPENED");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_GIF).body(PIXEL);
    }

    /** CTA click: records it, then redirects to the approved CTA link. */
    @GetMapping("/c/{token}")
    public ResponseEntity<String> click(@PathVariable String token) {
        Long id = links.verify(Purpose.CLICK, token);
        CampaignMessage m = id == null ? null : execution.recordTracked(id, "CLICKED");
        String target = m == null ? null : destination(m);
        if (target == null) return page(HttpStatus.NOT_FOUND, "Link not available", "This link is no longer available.");
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).cacheControl(CacheControl.noStore()).build();
    }

    /**
     * Unsubscribe confirmation. A GET only shows the button: security scanners
     * follow links in email, and a GET that unsubscribed would unsubscribe
     * people who never asked to be.
     */
    @GetMapping("/u/{token}")
    public ResponseEntity<String> unsubscribePage(@PathVariable String token) {
        if (links.verify(Purpose.UNSUBSCRIBE, token) == null) {
            return page(HttpStatus.NOT_FOUND, "Link not valid", "This unsubscribe link is not valid.");
        }
        return pageHtml(HttpStatus.OK, "Unsubscribe",
                "<p>Stop receiving these marketing emails?</p><form method=\"post\"><button type=\"submit\" "
                        + "style=\"padding:10px 18px;font-size:15px;\">Unsubscribe</button></form>");
    }

    /** The confirmation button, and RFC 8058 one-click unsubscribe from mail clients (List-Unsubscribe-Post). */
    @PostMapping("/u/{token}")
    public ResponseEntity<String> unsubscribe(@PathVariable String token) {
        Long id = links.verify(Purpose.UNSUBSCRIBE, token);
        if (id == null || execution.recordTracked(id, "UNSUBSCRIBED") == null) {
            return page(HttpStatus.NOT_FOUND, "Link not valid", "This unsubscribe link is not valid.");
        }
        return page(HttpStatus.OK, "Unsubscribed", "You have been unsubscribed and won't receive these marketing emails again.");
    }

    /**
     * Delivery events from the email provider, normalised to DeliveryEvent
     * (DELIVERED, BOUNCED, COMPLAINED, UNSUBSCRIBED, OPENED, CLICKED), matched
     * by providerMessageId (the Message-ID we sent) or messageId. Requires the
     * X-Marketing-Webhook-Secret header; disabled until a secret is configured.
     */
    @PostMapping("/webhooks/email")
    public ResponseEntity<Map<String, Integer>> webhook(@RequestHeader(value = "X-Marketing-Webhook-Secret", required = false) String secret,
                                                        @Valid @RequestBody DeliveryEventsRequest request) {
        String expected = settings.webhookSecret();
        if (expected == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        if (secret == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(Map.of("applied", execution.recordWebhookEvents(request.events())));
    }

    private String destination(CampaignMessage m) {
        String variant = m.getVariant() == null ? "A" : m.getVariant();
        String url = contents.findByCampaignIdAndChannelAndVariant(m.getCampaignId(), ContentChannel.EMAIL, variant)
                .map(v -> MarketingJson.readStringMap(v.getFieldsJson()).get("ctaUrl")).orElse(null);
        if (url == null || url.isBlank()) {
            url = campaigns.findById(m.getCampaignId()).map(MarketingCampaign::getLandingPageUrl).orElse(null);
        }
        return url != null && url.trim().matches("(?i)^https?://[^\\s<>\"']+$") ? url.trim() : null;
    }

    private static ResponseEntity<String> page(HttpStatus status, String title, String message) {
        return pageHtml(status, title, "<p>" + EmailRenderer.esc(message) + "</p>");
    }

    /** bodyHtml is fixed markup from this class, never user input. */
    private static ResponseEntity<String> pageHtml(HttpStatus status, String title, String bodyHtml) {
        String html = "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta name=\"robots\" content=\"noindex\">"
                + "<title>" + EmailRenderer.esc(title) + "</title></head>"
                + "<body style=\"font-family:Arial,Helvetica,sans-serif;max-width:480px;margin:64px auto;padding:0 16px;color:#1f2328;\">"
                + "<h1 style=\"font-size:20px;\">" + EmailRenderer.esc(title) + "</h1>" + bodyHtml + "</body></html>";
        return ResponseEntity.status(status)
                .header(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'")
                .cacheControl(CacheControl.noStore())
                .body(html);
    }
}
