package com.techcrm.crm.marketing.content;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.lead.LeadRepository;
import com.techcrm.crm.marketing.activity.ActivityService;
import com.techcrm.crm.marketing.ai.ContentOutputValidator;
import com.techcrm.crm.marketing.ai.ContentSource;
import com.techcrm.crm.marketing.api.MarketingDtos.ContentResponse;
import com.techcrm.crm.marketing.api.MarketingDtos.EmailPreview;
import com.techcrm.crm.marketing.approval.ApprovalService;
import com.techcrm.crm.marketing.campaign.CampaignStatus;
import com.techcrm.crm.marketing.campaign.MarketingCampaign;
import com.techcrm.crm.marketing.campaign.PipelineStatus;
import com.techcrm.crm.marketing.common.MarketingJson;
import com.techcrm.crm.marketing.common.UserNames;
import com.techcrm.crm.marketing.email.CampaignEmailComposer;
import com.techcrm.crm.marketing.content.ContentVariant.Provenance;
import com.techcrm.crm.marketing.content.ContentVariant.Status;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Content verification: edits, drafts, approvals and rejections of generated
 * content. Regeneration goes through the AI orchestrator.
 *
 * A human edit is held to the same structural checks as model output
 * (required fields, lengths, no script) but grounding findings become
 * warnings rather than errors: the Marketing Head is the source of truth and
 * may state facts the brief didn't, but should see that they did.
 */
@Service
public class ContentReviewService {

    private final ContentVariantRepository contents;
    private final ApprovalService approvals;
    private final ActivityService activity;
    private final UserNames userNames;
    private final CampaignEmailComposer composer;
    private final LeadRepository leads;

    public ContentReviewService(ContentVariantRepository contents, ApprovalService approvals, ActivityService activity,
                                UserNames userNames, CampaignEmailComposer composer, LeadRepository leads) {
        this.contents = contents;
        this.approvals = approvals;
        this.activity = activity;
        this.userNames = userNames;
        this.composer = composer;
        this.leads = leads;
    }

    /** The campaign's email content. Rows for retired channels (poster, SMS, ...) are kept in the database but not shown. */
    @Transactional(readOnly = true)
    public List<ContentResponse> list(MarketingCampaign c) {
        List<ContentVariant> all = contents.findByCampaignIdOrderByChannelAscVariantAsc(c.getId()).stream()
                .filter(v -> !v.getChannel().isLegacy()).toList();
        Map<Long, String> names = userNames.resolve(c.getOrganizationId(), all.stream().map(ContentVariant::getReviewedBy).toList());
        return all.stream().map(v -> toResponse(v, names)).toList();
    }

    @Transactional
    public ContentResponse update(AuthenticatedUser caller, MarketingCampaign c, Long contentId, Map<String, String> fields,
                                  boolean draft) {
        ContentVariant v = requireEditable(c, contentId);
        Map<String, String> before = MarketingJson.readStringMap(v.getFieldsJson());

        ContentOutputValidator.Result check = ContentOutputValidator.validate(v.getChannel(), fields,
                ContentSource.from(c).groundingText());
        List<String> structural = check.errors().stream().filter(ContentReviewService::isStructural).toList();
        if (!structural.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", structural));
        }
        List<String> warnings = new ArrayList<>(check.warnings());
        check.errors().stream().filter(e -> !isStructural(e))
                .map(e -> e + " (added by a person; confirm before approving)").forEach(warnings::add);

        v.setFieldsJson(MarketingJson.write(check.fields()));
        if (v.getProvenance() == Provenance.AI_GENERATED || v.getProvenance() == Provenance.SYSTEM_GENERATED) {
            v.setProvenance(Provenance.HUMAN_EDITED);
        }
        v.setValidationWarnings(MarketingJson.write(warnings));
        v.setStatus(draft ? Status.DRAFT : Status.PENDING_REVIEW);
        v.setReviewedBy(null);
        v.setReviewedAt(null);
        contents.save(v);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("channel", v.getChannel().name());
        detail.put("variant", v.getVariant());
        detail.put("changedFields", changed(before, check.fields()));
        activity.human(c.getOrganizationId(), c.getId(), caller.userId(), draft ? "CONTENT_DRAFT_SAVED" : "CONTENT_EDITED",
                (draft ? "Saved draft of " : "Edited ") + label(v), detail);
        approvals.recompute(c.getId(), caller.userId());
        return toResponse(v, Map.of());
    }

    @Transactional
    public ContentResponse decide(AuthenticatedUser caller, MarketingCampaign c, Long contentId, boolean approve, String note) {
        ContentVariant v = requireEditable(c, contentId);
        if (!approve && (note == null || note.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why the content is rejected; the note guides regeneration");
        }
        v.setStatus(approve ? Status.APPROVED : Status.REJECTED);
        v.setReviewedBy(caller.userId());
        v.setReviewedAt(OffsetDateTime.now());
        v.setReviewNote(note == null || note.isBlank() ? null : note.trim());
        contents.save(v);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("channel", v.getChannel().name());
        detail.put("variant", v.getVariant());
        detail.put("provenance", v.getProvenance().name());
        detail.put("warningsAtApproval", MarketingJson.readStringList(v.getValidationWarnings()).size());
        if (note != null) detail.put("note", note);
        activity.human(c.getOrganizationId(), c.getId(), caller.userId(), approve ? "CONTENT_APPROVED" : "CONTENT_REJECTED",
                (approve ? "Approved " : "Rejected ") + label(v) + " (" + v.getProvenance().name().replace('_', ' ').toLowerCase() + ")"
                        + (note != null && !note.isBlank() ? ": " + note : ""), detail);
        approvals.recompute(c.getId(), caller.userId());
        return toResponse(v, userNames.resolve(c.getOrganizationId(), List.of(caller.userId())));
    }

    /**
     * The email as a recipient would receive it: rendered HTML and plain text
     * with merge fields filled for the given lead, or sample values.
     */
    @Transactional(readOnly = true)
    public EmailPreview preview(MarketingCampaign c, Long contentId, Long leadId) {
        ContentVariant v = contents.findByIdAndCampaignId(contentId, c.getId())
                .filter(x -> !x.getChannel().isLegacy())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found"));
        Lead lead = leadId == null ? null : leads.findByIdAndOrganizationId(leadId, c.getOrganizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found"));
        return composer.preview(c, MarketingJson.readStringMap(v.getFieldsJson()), lead);
    }

    private ContentVariant requireEditable(MarketingCampaign c, Long contentId) {
        if (!CampaignStatus.EDITABLE.contains(c.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Content is locked once the campaign is " + c.getStatus().name().toLowerCase().replace('_', ' '));
        }
        if (c.getContentPipelineStatus() == PipelineStatus.RUNNING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wait for content generation to finish");
        }
        return contents.findByIdAndCampaignId(contentId, c.getId())
                .filter(v -> !v.getChannel().isLegacy())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found"));
    }

    /** Structural problems block a save; grounding findings on human text only warn. */
    private static boolean isStructural(String error) {
        return error.contains("characters, limit is") || error.contains("is required") || error.contains("script")
                || error.contains("must be an http(s) link");
    }

    private static List<String> changed(Map<String, String> before, Map<String, String> after) {
        Set<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        return keys.stream().filter(k -> !Objects.equals(before.get(k), after.get(k))).toList();
    }

    private static String label(ContentVariant v) {
        return "email (variant " + v.getVariant() + ")";
    }

    static ContentResponse toResponse(ContentVariant v, Map<Long, String> names) {
        return new ContentResponse(String.valueOf(v.getId()), v.getChannel().name(), v.getVariant(),
                MarketingJson.readStringMap(v.getFieldsJson()), v.getStatus().name(), v.getProvenance().name(),
                v.getModelName(), v.getGenerationCount(), MarketingJson.readStringList(v.getValidationWarnings()),
                v.getReviewNote(),
                v.getReviewedBy() == null ? null : names.getOrDefault(v.getReviewedBy(), "User " + v.getReviewedBy()),
                v.getReviewedAt(), v.getUpdatedAt());
    }
}
