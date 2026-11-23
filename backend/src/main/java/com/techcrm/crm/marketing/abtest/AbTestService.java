package com.techcrm.crm.marketing.abtest;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.marketing.abtest.AbTest.DecisionRule;
import com.techcrm.crm.marketing.abtest.AbTest.Element;
import com.techcrm.crm.marketing.abtest.AbTest.Metric;
import com.techcrm.crm.marketing.activity.ActivityService;
import com.techcrm.crm.marketing.api.MarketingDtos.AbTestRequest;
import com.techcrm.crm.marketing.api.MarketingDtos.AbTestResponse;
import com.techcrm.crm.marketing.api.MarketingDtos.Funnel;
import com.techcrm.crm.marketing.approval.ApprovalService;
import com.techcrm.crm.marketing.campaign.CampaignChannels;
import com.techcrm.crm.marketing.campaign.CampaignStatus;
import com.techcrm.crm.marketing.campaign.MarketingCampaign;
import com.techcrm.crm.marketing.content.ContentChannel;
import com.techcrm.crm.marketing.execution.CampaignMessage;
import com.techcrm.crm.marketing.execution.CampaignMessageRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A/B test configuration and decisions.
 *
 * The decision is never a model judgment. MANUAL waits for the Marketing Head
 * to choose after reviewing the measured results. HIGHEST_PRIMARY_METRIC picks
 * the variant with the higher measured primary metric, but only after the test
 * window has closed and both variants have reached the minimum sample; a tie
 * or a thin sample falls back to a manual decision.
 */
@Service
public class AbTestService {

    private final AbTestRepository repository;
    private final CampaignMessageRepository messages;
    private final ApprovalService approvals;
    private final ActivityService activity;

    public AbTestService(AbTestRepository repository, CampaignMessageRepository messages,
                         ApprovalService approvals, ActivityService activity) {
        this.repository = repository;
        this.messages = messages;
        this.approvals = approvals;
        this.activity = activity;
    }

    @Transactional
    public AbTestResponse configure(AuthenticatedUser caller, MarketingCampaign campaign, AbTestRequest req) {
        if (!CampaignStatus.EDITABLE.contains(campaign.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The A/B test can't change after the campaign is scheduled or launched");
        }
        Element element = Element.valueOf(req.testElement());
        List<ContentChannel> configured = CampaignChannels.configured(campaign);
        AbTest test = repository.findByCampaignId(campaign.getId()).orElseGet(() -> {
            AbTest t = new AbTest();
            t.setCampaignId(campaign.getId());
            return t;
        });
        test.setEnabled(req.enabled());
        test.setTestElement(element);
        test.setSplitAPct(req.splitAPct());
        test.setTestAudiencePct(req.testAudiencePct());
        test.setDurationHours(req.durationHours());
        test.setPrimaryMetric(Metric.valueOf(req.primaryMetric()));
        test.setDecisionRule(DecisionRule.valueOf(req.decisionRule()));
        test.setMinSamplePerVariant(req.minSamplePerVariant());
        repository.save(test);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("enabled", req.enabled());
        detail.put("element", element.name());
        detail.put("split", req.splitAPct() + "/" + (100 - req.splitAPct()));
        detail.put("testAudiencePct", req.testAudiencePct());
        detail.put("durationHours", req.durationHours());
        detail.put("primaryMetric", req.primaryMetric());
        detail.put("decisionRule", req.decisionRule());
        activity.human(campaign.getOrganizationId(), campaign.getId(), caller.userId(), "AB_TEST_CONFIGURED",
                req.enabled() ? "Configured A/B test on " + pretty(element.name()) + " ("
                        + req.splitAPct() + "% A / " + (100 - req.splitAPct()) + "% B)" : "Disabled A/B testing", detail);
        approvals.recompute(campaign.getId(), caller.userId());
        return toResponse(test, configured, Map.of());
    }

    /** Marketing Head's choice after reviewing the measured results. */
    @Transactional
    public AbTestResponse chooseWinner(AuthenticatedUser caller, MarketingCampaign campaign, String variant, String note,
                                       Map<String, Funnel> measured) {
        AbTest test = repository.findByCampaignId(campaign.getId()).filter(AbTest::getEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "This campaign has no A/B test"));
        if (test.getStatus() == AbTest.Status.CONFIGURED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The test hasn't started yet");
        }
        if (test.getStatus() == AbTest.Status.DECIDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A variant has already been selected");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("variant", variant);
        detail.put("primaryMetric", test.getPrimaryMetric().name());
        measured.forEach((k, f) -> detail.put("variant" + k + "Metric", metric(f, test.getPrimaryMetric())));
        decide(test, variant, caller.userId(), note == null || note.isBlank() ? "Selected by the Marketing Head" : note);
        activity.human(campaign.getOrganizationId(), campaign.getId(), caller.userId(), "AB_WINNER_SELECTED",
                "Selected variant " + variant + " after reviewing measured results", detail);
        return toResponse(test, CampaignChannels.configured(campaign), Map.of());
    }

    /** Called by the dispatcher when a running test's window closes. */
    @Transactional
    public void closeWindow(AbTest test, MarketingCampaign campaign, Map<String, Funnel> measured) {
        test.setStatus(AbTest.Status.AWAITING_DECISION);
        repository.save(test);
        if (test.getDecisionRule() != DecisionRule.HIGHEST_PRIMARY_METRIC) {
            activity.system(campaign.getOrganizationId(), campaign.getId(), "AB_TEST_WINDOW_CLOSED",
                    "A/B test window closed; waiting for the Marketing Head to choose a variant", null);
            return;
        }
        Funnel a = measured.get("A");
        Funnel b = measured.get("B");
        long sampleA = sample(a);
        long sampleB = sample(b);
        Double ma = a == null ? null : metric(a, test.getPrimaryMetric());
        Double mb = b == null ? null : metric(b, test.getPrimaryMetric());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("primaryMetric", test.getPrimaryMetric().name());
        detail.put("variantAMetric", ma);
        detail.put("variantBMetric", mb);
        detail.put("variantASample", sampleA);
        detail.put("variantBSample", sampleB);
        detail.put("minSamplePerVariant", test.getMinSamplePerVariant());

        if (sampleA < test.getMinSamplePerVariant() || sampleB < test.getMinSamplePerVariant() || ma == null || mb == null) {
            activity.system(campaign.getOrganizationId(), campaign.getId(), "AB_TEST_NEEDS_DECISION",
                    "A/B test window closed without the minimum sample for an automatic decision; choose a variant manually", detail);
            return;
        }
        if (ma.equals(mb)) {
            activity.system(campaign.getOrganizationId(), campaign.getId(), "AB_TEST_NEEDS_DECISION",
                    "A/B test ended in a tie on " + pretty(test.getPrimaryMetric().name()) + "; choose a variant manually", detail);
            return;
        }
        String winner = ma > mb ? "A" : "B";
        String note = "Decision rule: highest " + pretty(test.getPrimaryMetric().name()) + ". Measured A " + ma + "% vs B " + mb + "%.";
        decide(test, winner, null, note);
        activity.system(campaign.getOrganizationId(), campaign.getId(), "AB_WINNER_SELECTED",
                "Variant " + winner + " selected by the configured decision rule. " + note, detail);
    }

    private void decide(AbTest test, String variant, Long userId, String note) {
        test.setWinnerVariant(variant);
        test.setDecidedBy(userId);
        test.setDecidedAt(OffsetDateTime.now());
        test.setDecisionNote(note);
        test.setStatus(AbTest.Status.DECIDED);
        repository.save(test);
        // Release the holdout with the chosen variant.
        List<CampaignMessage> held = messages.findByCampaignIdAndStatus(test.getCampaignId(), CampaignMessage.Status.HELD);
        for (CampaignMessage m : held) {
            m.setVariant(variant);
            m.setStatus(CampaignMessage.Status.QUEUED);
        }
        messages.saveAll(held);
    }

    /** Emails that reached a mailbox as far as we know: accepted and not bounced. */
    private static long sample(Funnel f) {
        return f == null ? 0 : Math.max(0, f.sent() - f.bounced());
    }

    public static Double metric(Funnel f, Metric metric) {
        return switch (metric) {
            case OPEN_RATE -> f.openRate();
            case CLICK_RATE -> f.clickRate();
            case CONVERSION_RATE -> f.conversionRate();
            case RESPONSE_RATE -> f.responseRate();
        };
    }

    public static AbTestResponse toResponse(AbTest t, List<ContentChannel> configured, Map<Long, String> names) {
        ContentChannel tested = CampaignChannels.testedChannel(t.getTestElement(), configured);
        return new AbTestResponse(t.getEnabled(), t.getTestElement().name(), tested == null ? null : tested.name(),
                t.getSplitAPct(), t.getTestAudiencePct(), t.getDurationHours(), t.getPrimaryMetric().name(),
                t.getDecisionRule().name(), t.getMinSamplePerVariant(), t.getStatus().name(), t.getStartedAt(),
                t.getEndsAt(), t.getWinnerVariant(), t.getDecidedBy() == null ? null : names.getOrDefault(t.getDecidedBy(), "User " + t.getDecidedBy()),
                t.getDecidedAt(), t.getDecisionNote());
    }

    private static String pretty(String s) {
        return s.replace('_', ' ').toLowerCase();
    }
}
