package com.techcrm.crm.marketing.ai;

import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.lead.LeadRepository;
import com.techcrm.crm.marketing.abtest.AbTest;
import com.techcrm.crm.marketing.abtest.AbTestRepository;
import com.techcrm.crm.marketing.activity.ActivityService;
import com.techcrm.crm.marketing.ai.ContentGenerationAi.VariantSpec;
import com.techcrm.crm.marketing.ai.LeadEvaluationValidator.LeadEvaluation;
import com.techcrm.crm.marketing.ai.LlmService.LlmUnavailableException;
import com.techcrm.crm.marketing.api.MarketingDtos.Criterion;
import com.techcrm.crm.marketing.api.MarketingDtos.RuleResult;
import com.techcrm.crm.marketing.approval.ApprovalService;
import com.techcrm.crm.marketing.campaign.*;
import com.techcrm.crm.marketing.common.MarketingJson;
import com.techcrm.crm.marketing.content.ContentChannel;
import com.techcrm.crm.marketing.content.ContentVariant;
import com.techcrm.crm.marketing.content.ContentVariant.Provenance;
import com.techcrm.crm.marketing.content.ContentVariantRepository;
import com.techcrm.crm.marketing.email.EmailSettings;
import com.techcrm.crm.marketing.email.EmailSuppression;
import com.techcrm.crm.marketing.email.SuppressionService;
import com.techcrm.crm.marketing.leads.CampaignLead;
import com.techcrm.crm.marketing.leads.CampaignLead.Eligibility;
import com.techcrm.crm.marketing.leads.CampaignLeadRepository;
import com.techcrm.crm.marketing.leads.rules.LeadFacts;
import com.techcrm.crm.marketing.leads.rules.RuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * The AI orchestration layer: turns a submitted campaign into two independent
 * background runs, Pipeline A (per-lead evaluation) and Pipeline B (content
 * generation), and records their results for human review.
 *
 * <p>The two runs are submitted to the executor together and share nothing
 * but the campaign id, so content is usually ready long before the last lead
 * is scored. Each run carries a run number; restarting a pipeline bumps it,
 * and a superseded worker notices on its next write and stops.
 *
 * <p>Nothing produced here is ever used directly. Leads land as PENDING and
 * content as PENDING_REVIEW, and only a Marketing Head's decision moves them.
 */
@Service
public class MarketingAiOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MarketingAiOrchestrator.class);

    /** Consecutive "model unreachable" errors after which the rest of a run uses the fallback. */
    private static final int MODEL_DOWN_AFTER = 3;

    private final MarketingCampaignRepository campaigns;
    private final CampaignCriterionRepository criteriaRepo;
    private final LeadRepository leads;
    private final CampaignLeadRepository campaignLeads;
    private final ContentVariantRepository contents;
    private final AbTestRepository abTests;
    private final LeadEvaluationAi leadAi;
    private final ContentGenerationAi contentAi;
    private final ApprovalService approvals;
    private final ActivityService activity;
    private final SuppressionService suppressions;
    private final Executor executor;
    private final TransactionTemplate tx;
    private final boolean fallbackEnabled;
    private final int maxLeadsPerRun;

    public MarketingAiOrchestrator(MarketingCampaignRepository campaigns, CampaignCriterionRepository criteriaRepo,
                                   LeadRepository leads, CampaignLeadRepository campaignLeads,
                                   ContentVariantRepository contents, AbTestRepository abTests,
                                   LeadEvaluationAi leadAi, ContentGenerationAi contentAi,
                                   ApprovalService approvals, ActivityService activity, SuppressionService suppressions,
                                   @Qualifier("marketingAiExecutor") Executor executor,
                                   PlatformTransactionManager transactionManager,
                                   @Value("${marketing.ai.fallback-enabled:true}") boolean fallbackEnabled,
                                   @Value("${marketing.ai.max-leads-per-run:500}") int maxLeadsPerRun) {
        this.campaigns = campaigns;
        this.criteriaRepo = criteriaRepo;
        this.leads = leads;
        this.campaignLeads = campaignLeads;
        this.contents = contents;
        this.abTests = abTests;
        this.leadAi = leadAi;
        this.contentAi = contentAi;
        this.approvals = approvals;
        this.activity = activity;
        this.suppressions = suppressions;
        this.executor = executor;
        this.tx = new TransactionTemplate(transactionManager);
        this.fallbackEnabled = fallbackEnabled;
        this.maxLeadsPerRun = maxLeadsPerRun;
    }

    public boolean fallbackEnabled() {
        return fallbackEnabled;
    }

    /* ================================================================
     * Starting runs
     * ================================================================ */

    /** Submits a campaign: validates it, then starts both pipelines in parallel. */
    public void submit(Long campaignId, Long actorUserId) {
        startLeadPipeline(campaignId, actorUserId);
        startContentPipeline(campaignId, actorUserId, null, "A", null);
    }

    /**
     * (Re)starts lead evaluation. Discards the previous evaluation and every
     * decision made on it: a new run judges the lead database as it is now.
     */
    public void startLeadPipeline(Long campaignId, Long actorUserId) {
        int run = tx.execute(status -> {
            MarketingCampaign c = lockEditable(campaignId);
            if (c.getLeadPipelineStatus() == PipelineStatus.RUNNING) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Lead evaluation is already running");
            }
            campaignLeads.deleteByCampaignId(campaignId);
            c.setLeadPipelineRun(c.getLeadPipelineRun() + 1);
            c.setLeadPipelineStatus(PipelineStatus.RUNNING);
            c.setLeadPipelineDone(0);
            c.setLeadPipelineTotal(0);
            c.setLeadPipelineError(null);
            c.setStatus(CampaignStatus.AI_PROCESSING);
            campaigns.save(c);
            activity.human(c.getOrganizationId(), campaignId, actorUserId, "LEAD_EVALUATION_REQUESTED",
                    "Requested AI lead evaluation", null);
            approvals.withdrawAll(c);
            return c.getLeadPipelineRun();
        });
        executor.execute(() -> runLeadPipeline(campaignId, run));
    }

    /**
     * (Re)generates content. channels null = campaign copy plus every
     * configured channel. Variant B requires an enabled A/B test and writes
     * only the tested channel.
     */
    public void startContentPipeline(Long campaignId, Long actorUserId, List<ContentChannel> channels, String variant,
                                     String instructions) {
        String v = "B".equals(variant) ? "B" : "A";
        record Start(int run, List<ContentChannel> channels) {
        }
        Start start = tx.execute(status -> {
            MarketingCampaign c = lockEditable(campaignId);
            if (c.getContentPipelineStatus() == PipelineStatus.RUNNING) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Content generation is already running");
            }
            List<ContentChannel> target;
            if ("B".equals(v)) {
                AbTest ab = abTests.findByCampaignId(campaignId).filter(AbTest::getEnabled)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Enable an A/B test before generating variant B"));
                ContentChannel tested = CampaignChannels.testedChannel(ab.getTestElement(), CampaignChannels.configured(c));
                if (tested == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "The A/B test element needs a channel this campaign doesn't use");
                }
                if (contents.findByCampaignIdAndChannelAndVariant(campaignId, tested, "A").isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Generate variant A first");
                }
                target = List.of(tested);
            } else {
                List<ContentChannel> all = CampaignChannels.generated(c);
                target = channels == null || channels.isEmpty() ? all : channels.stream().filter(all::contains).toList();
                if (target.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "None of those channels are configured for this campaign");
                }
            }
            c.setContentPipelineRun(c.getContentPipelineRun() + 1);
            c.setContentPipelineStatus(PipelineStatus.RUNNING);
            c.setContentPipelineError(null);
            c.setStatus(CampaignStatus.AI_PROCESSING);
            campaigns.save(c);
            activity.human(c.getOrganizationId(), campaignId, actorUserId, "CONTENT_GENERATION_REQUESTED",
                    "Requested AI content for " + describe(target) + " (variant " + v + ")"
                            + (instructions != null && !instructions.isBlank() ? " with feedback" : ""),
                    instructions == null ? null : Map.of("feedback", instructions));
            return new Start(c.getContentPipelineRun(), target);
        });
        executor.execute(() -> runContentPipeline(campaignId, start.run(), start.channels(), v, instructions));
    }

    private MarketingCampaign lockEditable(Long campaignId) {
        MarketingCampaign c = campaigns.findForUpdate(campaignId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
        if (!CampaignStatus.EDITABLE.contains(c.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "AI processing can't run once a campaign is " + c.getStatus().name().toLowerCase().replace('_', ' '));
        }
        return c;
    }

    /* ================================================================
     * Pipeline A: lead evaluation
     * ================================================================ */

    private record LeadRunInput(MarketingCampaign campaign, Map<String, Object> brief, List<Criterion> criteria,
                                List<Lead> leads, int available, Map<String, EmailSuppression.Reason> suppressed) {
    }

    void runLeadPipeline(Long campaignId, int run) {
        Long orgId = null;
        try {
            LeadRunInput input = tx.execute(status -> {
                MarketingCampaign c = campaigns.findById(campaignId).orElseThrow();
                List<Criterion> criteria = criteriaRepo.findByCampaignIdOrderByPosition(campaignId).stream()
                        .map(k -> new Criterion(k.getField(), k.getOperator(), k.getValue())).toList();
                List<Lead> all = new ArrayList<>(leads.findByOrganizationId(c.getOrganizationId()));
                all.sort(Comparator.comparing(Lead::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
                List<Lead> capped = all.size() > maxLeadsPerRun ? all.subList(0, maxLeadsPerRun) : all;
                Map<String, EmailSuppression.Reason> suppressed = suppressions.lookup(c.getOrganizationId(),
                        capped.stream().map(Lead::getEmail).filter(Objects::nonNull).toList());
                return new LeadRunInput(c, ContentSource.from(c).audienceBrief(c), criteria, List.copyOf(capped), all.size(),
                        suppressed);
            });
            MarketingCampaign c = input.campaign();
            orgId = c.getOrganizationId();
            tx.executeWithoutResult(s -> campaigns.startLeadProgress(campaignId, run, input.leads().size()));

            if (input.leads().isEmpty()) {
                finishLeads(campaignId, run, orgId, PipelineStatus.COMPLETED, null,
                        "No leads in the CRM to evaluate", Map.of("evaluated", 0));
                return;
            }

            int consecutiveFailures = 0;
            boolean modelDown = false;
            String modelDownReason = null;
            int byModel = 0;
            int byRules = 0;
            int eligible = 0;
            int guarded = 0;
            int unreachable = 0;

            for (int i = 0; i < input.leads().size(); i++) {
                if (superseded(campaignId, run)) {
                    log.info("Lead evaluation run {} for campaign {} superseded; stopping", run, campaignId);
                    return;
                }
                Lead lead = input.leads().get(i);
                LeadFacts facts = LeadFacts.from(lead);
                RuleEngine.Evaluation rules = RuleEngine.evaluate(input.criteria(), c.getRuleMatch(), facts);

                CampaignLead row = new CampaignLead();
                row.setCampaignId(campaignId);
                row.setLeadId(lead.getId());
                row.setRuleResults(MarketingJson.write(rules.results()));
                row.setEvaluatedAt(OffsetDateTime.now());

                // Mandatory for an email campaign, checked before the model: a
                // lead without a usable address, or one that has unsubscribed,
                // bounced or complained, can't be eligible whatever its fit.
                String blocked = emailBlock(lead, input.suppressed());
                if (blocked != null) {
                    applyEmailBlock(row, blocked);
                    unreachable++;
                }

                String fallbackReason = modelDownReason;
                if (!modelDown && blocked == null) {
                    try {
                        LeadEvaluationAi.Result result = leadAi.evaluate(input.brief(), facts, rules);
                        consecutiveFailures = 0;
                        applyModel(row, result.evaluation(), result.model(), rules);
                        row.setAiInput(result.userPrompt());
                        byModel++;
                        if (row.getRuleGuardApplied()) guarded++;
                    } catch (LlmUnavailableException e) {
                        consecutiveFailures++;
                        fallbackReason = e.getMessage();
                        if (consecutiveFailures >= MODEL_DOWN_AFTER) {
                            modelDown = true;
                            modelDownReason = e.getMessage();
                        }
                    } catch (InvalidAiOutputException e) {
                        fallbackReason = "The AI reply failed validation (" + e.getMessage() + ")";
                    }
                }
                if (row.getEvaluationSource() == null) {
                    if (!fallbackEnabled || input.criteria().isEmpty()) {
                        throw new PipelineAbort("The AI model could not evaluate leads (" + fallbackReason + ")"
                                + (input.criteria().isEmpty() ? " and the campaign has no rules to fall back on." : "."));
                    }
                    applyRules(row, rules, fallbackReason);
                    byRules++;
                }
                if (row.getAiEligibility() == Eligibility.ELIGIBLE) eligible++;

                int done = i + 1;
                boolean saved = Boolean.TRUE.equals(tx.execute(s -> {
                    if (!Objects.equals(campaigns.currentLeadRun(campaignId), run)) return false;
                    campaignLeads.save(row);
                    campaigns.updateLeadProgress(campaignId, run, done);
                    return true;
                }));
                if (!saved) return;
            }

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("evaluated", input.leads().size());
            detail.put("eligible", eligible);
            detail.put("byModel", byModel);
            detail.put("byRulesEngine", byRules);
            detail.put("ruleGuardApplied", guarded);
            detail.put("notContactableByEmail", unreachable);
            if (input.available() > input.leads().size()) detail.put("notEvaluatedOverCap", input.available() - input.leads().size());
            String summary = "Evaluated " + input.leads().size() + " leads individually: " + eligible + " eligible"
                    + (byRules > 0 ? " (" + byRules + " by the rules engine because the AI model was unavailable)" : "")
                    + (guarded > 0 ? "; rule check overruled " + guarded + " AI eligibility decisions" : "")
                    + (unreachable > 0 ? "; " + unreachable + " not contactable by email" : "");
            finishLeads(campaignId, run, orgId, PipelineStatus.COMPLETED, null, summary, detail);
        } catch (PipelineAbort e) {
            finishLeads(campaignId, run, orgId, PipelineStatus.FAILED, e.getMessage(), e.getMessage(), null);
        } catch (Exception e) {
            log.error("Lead evaluation failed for campaign {}", campaignId, e);
            finishLeads(campaignId, run, orgId, PipelineStatus.FAILED,
                    "Lead evaluation stopped unexpectedly. Run it again; if it keeps failing, check the server log.",
                    "Lead evaluation failed", null);
        }
    }

    /**
     * Stores the model's evaluation, then applies the rule guard: a lead that
     * definitively fails the Marketing Head's required rules cannot be
     * recommended as eligible. The model's score and reasoning are kept exactly
     * as returned; the guard is recorded so the reviewer sees both.
     */
    static void applyModel(CampaignLead row, LeadEvaluation ev, String model, RuleEngine.Evaluation rules) {
        List<String> negatives = new ArrayList<>(ev.negativeFactors());
        Eligibility eligibility = ev.eligibility();
        if (eligibility == Eligibility.ELIGIBLE && rules.combined() == RuleEngine.Outcome.FAIL) {
            eligibility = Eligibility.NOT_ELIGIBLE;
            row.setRuleGuardApplied(true);
            for (RuleResult failed : rules.failed()) {
                negatives.add("Fails campaign rule: " + failed.label()
                        + (failed.actual() != null ? " (lead: " + failed.actual() + ")" : ""));
            }
        }
        row.setAiScore(ev.score());
        row.setAiEligibility(eligibility);
        row.setAiConfidence(ev.confidence());
        row.setPositiveFactors(MarketingJson.write(ev.positiveFactors()));
        row.setNegativeFactors(MarketingJson.write(negatives));
        row.setAiReason(ev.reason());
        row.setEvaluationSource(CampaignLead.Source.AI_MODEL);
        row.setModelName(model);
    }

    /** Why a lead can't receive campaign email, or null when it can. */
    static String emailBlock(Lead lead, Map<String, EmailSuppression.Reason> suppressed) {
        if (!EmailSettings.isAddress(lead.getEmail())) {
            return lead.getEmail() == null || lead.getEmail().isBlank() ? "No email address on the lead" : "The lead's email address is not valid";
        }
        EmailSuppression.Reason reason = suppressed.get(EmailSettings.normalise(lead.getEmail()));
        return reason == null ? null : "Email address " + SuppressionService.describe(reason);
    }

    /** Not contactable by email: not eligible, without a model call or a score. */
    static void applyEmailBlock(CampaignLead row, String why) {
        row.setAiScore(null);
        row.setAiEligibility(Eligibility.NOT_ELIGIBLE);
        row.setAiConfidence(null);
        row.setPositiveFactors(MarketingJson.write(List.of()));
        row.setNegativeFactors(MarketingJson.write(List.of("Mandatory: " + why)));
        row.setAiReason("Not evaluated by the AI: an email campaign can only include leads that can be emailed. " + why + ".");
        row.setEvaluationSource(CampaignLead.Source.EMAIL_CHECK);
        row.setModelName(null);
        row.setAiInput(null);
    }

    /** Deterministic evaluation from the rules alone, used only when the model can't answer. */
    static void applyRules(CampaignLead row, RuleEngine.Evaluation rules, String why) {
        long pass = rules.count(RuleEngine.Outcome.PASS);
        long unknown = rules.count(RuleEngine.Outcome.UNKNOWN);
        int n = rules.results().size();
        int score = n == 0 ? 50 : (int) Math.round(100.0 * (pass + 0.5 * unknown) / n);

        List<String> positives = new ArrayList<>();
        List<String> negatives = new ArrayList<>();
        for (RuleResult r : rules.results()) {
            switch (RuleEngine.Outcome.valueOf(r.outcome())) {
                case PASS -> positives.add("Meets rule: " + r.label() + " (lead: " + r.actual() + ")");
                case FAIL -> negatives.add("Fails rule: " + r.label() + " (lead: " + r.actual() + ")");
                case UNKNOWN -> negatives.add(r.actual() == null
                        ? "No data for: " + r.label()
                        : "Can't confirm: " + r.label() + " (lead: " + r.actual() + ")");
            }
        }
        boolean isEligible = rules.combined() == RuleEngine.Outcome.PASS;
        row.setAiScore(score);
        row.setAiEligibility(isEligible ? Eligibility.ELIGIBLE : Eligibility.NOT_ELIGIBLE);
        row.setAiConfidence(null);
        row.setPositiveFactors(MarketingJson.write(positives));
        row.setNegativeFactors(MarketingJson.write(negatives));
        row.setAiReason("Evaluated by the rules engine, not the AI model" + (why != null ? " (" + why + ")" : "")
                + ". " + pass + " of " + n + " rules met" + (unknown > 0 ? ", " + unknown + " could not be checked" : "")
                + ". Score is the share of rules met; review this lead's details before deciding.");
        row.setEvaluationSource(CampaignLead.Source.RULES_ENGINE);
        row.setModelName(null);
        row.setAiInput(null);
    }

    private boolean superseded(Long campaignId, int run) {
        Integer current = campaigns.currentLeadRun(campaignId);
        return current == null || current != run;
    }

    private void finishLeads(Long campaignId, int run, Long orgId, PipelineStatus status, String error,
                             String summary, Object detail) {
        Boolean finished = tx.execute(s -> campaigns.finishLeadPipeline(campaignId, run, status, clip(error)) > 0);
        if (!Boolean.TRUE.equals(finished)) return;
        tx.executeWithoutResult(s -> {
            if (orgId != null) {
                if (status == PipelineStatus.COMPLETED) activity.ai(orgId, campaignId, "LEADS_EVALUATED", summary, detail);
                else activity.system(orgId, campaignId, "LEAD_EVALUATION_FAILED", summary, detail);
            }
        });
        approvals.recompute(campaignId, null);
    }

    /* ================================================================
     * Pipeline B: content generation
     * ================================================================ */

    private record ContentRunInput(MarketingCampaign campaign, ContentSource source, VariantSpec variant) {
    }

    void runContentPipeline(Long campaignId, int run, List<ContentChannel> channels, String variant, String instructions) {
        Long orgId = null;
        try {
            ContentRunInput input = tx.execute(status -> {
                MarketingCampaign c = campaigns.findById(campaignId).orElseThrow();
                VariantSpec spec = null;
                if ("B".equals(variant)) {
                    AbTest ab = abTests.findByCampaignId(campaignId).orElseThrow();
                    Map<ContentChannel, Map<String, String>> a = new LinkedHashMap<>();
                    for (ContentChannel ch : channels) {
                        contents.findByCampaignIdAndChannelAndVariant(campaignId, ch, "A")
                                .ifPresent(v -> a.put(ch, MarketingJson.readStringMap(v.getFieldsJson())));
                    }
                    spec = new VariantSpec(describeElement(ab.getTestElement()), a);
                }
                return new ContentRunInput(c, ContentSource.from(c), spec);
            });
            orgId = input.campaign().getOrganizationId();

            Map<ContentChannel, ContentOutputValidator.Result> accepted = new LinkedHashMap<>();
            Map<ContentChannel, List<String>> rejected = new LinkedHashMap<>();
            String model = null;
            String prompt = null;
            try {
                ContentGenerationAi.Result result = contentAi.generate(input.source(), channels, input.variant(), instructions);
                accepted.putAll(result.accepted());
                rejected.putAll(result.rejected());
                model = result.model();
                prompt = result.userPrompt();
            } catch (LlmUnavailableException e) {
                for (ContentChannel ch : channels) rejected.put(ch, List.of(e.getMessage()));
            }

            final String modelName = model;
            // Kept on template fallbacks too: if the Marketing Head rewrites the
            // template and approves it, that is a human answer to this prompt.
            final String aiInput = prompt;
            final Long org = orgId;
            List<String> failures = new ArrayList<>();
            Boolean current = tx.execute(s -> {
                MarketingCampaign fresh = campaigns.findById(campaignId).orElseThrow();
                if (fresh.getContentPipelineRun() != run) return false;
                for (ContentChannel ch : channels) {
                    ContentOutputValidator.Result ok = accepted.get(ch);
                    if (ok != null) {
                        upsert(campaignId, ch, variant, ok.fields(), Provenance.AI_GENERATED, modelName, ok.warnings(),
                                ContentVariant.Status.PENDING_REVIEW, aiInput);
                        activity.ai(org, campaignId, "CONTENT_GENERATED",
                                "Generated " + channelLabel(ch) + " content (variant " + variant + ")",
                                Map.of("channel", ch.name(), "variant", variant, "warnings", ok.warnings()));
                        continue;
                    }
                    List<String> reasons = rejected.getOrDefault(ch, List.of("No output"));
                    if (!fallbackEnabled) {
                        failures.add(channelLabel(ch) + ": " + String.join("; ", reasons));
                        continue;
                    }
                    List<String> warnings = new ArrayList<>();
                    warnings.add("AI output was not used: " + String.join("; ", reasons.subList(0, Math.min(3, reasons.size()))));
                    Map<String, String> fields;
                    ContentVariant.Status status;
                    if ("B".equals(variant)) {
                        fields = input.variant().variantA().getOrDefault(ch, Map.of());
                        warnings.add("Variant B starts as a copy of variant A; edit the tested element before approving.");
                        status = ContentVariant.Status.DRAFT;
                    } else {
                        fields = TemplateContentComposer.compose(ch, input.source());
                        warnings.add("Drafted from your campaign inputs by a template. Edit before approving.");
                        status = ContentVariant.Status.PENDING_REVIEW;
                    }
                    upsert(campaignId, ch, variant, fields, Provenance.SYSTEM_GENERATED, null, warnings, status, aiInput);
                    activity.system(org, campaignId, "CONTENT_TEMPLATE_USED",
                            "Drafted " + channelLabel(ch) + " (variant " + variant + ") from a template because the AI output could not be used",
                            Map.of("channel", ch.name(), "variant", variant, "reasons", reasons));
                }
                return true;
            });
            if (!Boolean.TRUE.equals(current)) return;

            boolean anyProduced = failures.size() < channels.size();
            finishContent(campaignId, run, anyProduced ? PipelineStatus.COMPLETED : PipelineStatus.FAILED,
                    failures.isEmpty() ? null : clip("Some content could not be generated: " + String.join(" | ", failures)));
        } catch (Exception e) {
            log.error("Content generation failed for campaign {}", campaignId, e);
            final Long org = orgId;
            finishContent(campaignId, run, PipelineStatus.FAILED,
                    "Content generation stopped unexpectedly. Run it again; if it keeps failing, check the server log.");
            if (org != null) {
                tx.executeWithoutResult(s -> activity.system(org, campaignId, "CONTENT_GENERATION_FAILED",
                        "Content generation failed", null));
            }
        }
    }

    private void upsert(Long campaignId, ContentChannel channel, String variant, Map<String, String> fields,
                        Provenance provenance, String model, List<String> warnings, ContentVariant.Status status,
                        String aiInput) {
        ContentVariant v = contents.findByCampaignIdAndChannelAndVariant(campaignId, channel, variant).orElse(null);
        if (v == null) {
            v = new ContentVariant();
            v.setCampaignId(campaignId);
            v.setChannel(channel);
            v.setVariant(variant);
            v.setGenerationCount(1);
        } else {
            v.setGenerationCount(v.getGenerationCount() + 1);
        }
        v.setFieldsJson(MarketingJson.write(fields));
        v.setProvenance(provenance);
        v.setModelName(model);
        v.setAiInput(aiInput);
        v.setValidationWarnings(MarketingJson.write(warnings));
        v.setStatus(status);
        v.setReviewNote(null);
        v.setReviewedBy(null);
        v.setReviewedAt(null);
        contents.save(v);
    }

    private void finishContent(Long campaignId, int run, PipelineStatus status, String error) {
        Boolean finished = tx.execute(s -> campaigns.finishContentPipeline(campaignId, run, status, clip(error)) > 0);
        if (Boolean.TRUE.equals(finished)) approvals.recompute(campaignId, null);
    }

    /* ================================================================
     * Recovery
     * ================================================================ */

    /**
     * A restart kills in-flight workers, which would leave their pipelines
     * RUNNING forever. Mark them failed so the Marketing Head can rerun them.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        List<Long> touched = new ArrayList<>();
        tx.executeWithoutResult(s -> {
            for (MarketingCampaign c : campaigns.findByLeadPipelineStatusOrContentPipelineStatus(
                    PipelineStatus.RUNNING, PipelineStatus.RUNNING)) {
                if (c.getLeadPipelineStatus() == PipelineStatus.RUNNING) {
                    c.setLeadPipelineStatus(PipelineStatus.FAILED);
                    c.setLeadPipelineError("Interrupted by a server restart. Run lead evaluation again.");
                }
                if (c.getContentPipelineStatus() == PipelineStatus.RUNNING) {
                    c.setContentPipelineStatus(PipelineStatus.FAILED);
                    c.setContentPipelineError("Interrupted by a server restart. Generate content again.");
                }
                campaigns.save(c);
                activity.system(c.getOrganizationId(), c.getId(), "PIPELINE_INTERRUPTED",
                        "AI processing was interrupted by a server restart", null);
                touched.add(c.getId());
            }
        });
        touched.forEach(id -> approvals.recompute(id, null));
        if (!touched.isEmpty()) log.warn("Marked AI pipelines of {} campaign(s) as interrupted", touched.size());
    }

    /* ================================================================ */

    private static final class PipelineAbort extends RuntimeException {
        PipelineAbort(String message) {
            super(message);
        }
    }

    private static String describe(List<ContentChannel> channels) {
        return channels.stream().map(MarketingAiOrchestrator::channelLabel).reduce((a, b) -> a + ", " + b).orElse("content");
    }

    static String channelLabel(ContentChannel ch) {
        return ch == ContentChannel.EMAIL ? "email" : ch.name().toLowerCase();
    }

    /**
     * The element name the copywriter sees for variant B. The trained element
     * names are passed unchanged; the email-only additions are spelled out.
     */
    static String describeElement(AbTest.Element element) {
        return switch (element) {
            case PREVIEW_TEXT -> "PREVIEW_TEXT (the email preheader field)";
            case EMAIL_STRUCTURE -> "EMAIL_STRUCTURE (how the email is organised: order and format of greeting, body, "
                    + "offer details and call-to-action; the facts stay the same)";
            default -> element.name();
        };
    }

    private static String clip(String s) {
        if (s == null) return null;
        return s.length() <= 500 ? s : s.substring(0, 499) + "…";
    }
}
