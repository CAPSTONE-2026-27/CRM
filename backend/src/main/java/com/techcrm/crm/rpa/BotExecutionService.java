package com.techcrm.crm.rpa;

import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.lead.LeadRepository;
import com.techcrm.crm.lead.LeadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Executes the built-in RPA bots.
 *
 * The Node implementation used BullMQ on Redis; here the same three bots run on
 * Spring's own primitives — {@code @Async} for the event- and manually-triggered
 * ones and {@code @Scheduled} for the hourly sweep — so no broker is needed.
 * The trade-off is that queued work is in-process: a restart drops anything
 * mid-flight, and there is no cross-instance coordination. That is acceptable
 * while the app runs as a single instance; a real broker would be needed before
 * scaling out.
 *
 * Every invocation records an RpaBotRun so the control room shows the same
 * history regardless of what triggered it.
 */
@Service
public class BotExecutionService {

    private static final Logger log = LoggerFactory.getLogger(BotExecutionService.class);

    public static final String LEAD_ENRICHMENT = "Lead enrichment bot";
    public static final String FOLLOW_UP_SEQUENCING = "Follow-up sequencing bot";
    public static final String CASE_ROUTING = "Case routing bot";

    /** A lead untouched for this long is considered to need a nudge. */
    private static final int STALE_LEAD_DAYS = 3;

    private final RpaBotRepository botRepository;
    private final RpaBotRunRepository runRepository;
    private final LeadRepository leadRepository;
    private final LeadService leadService;

    public BotExecutionService(RpaBotRepository botRepository,
                               RpaBotRunRepository runRepository,
                               LeadRepository leadRepository,
                               LeadService leadService) {
        this.botRepository = botRepository;
        this.runRepository = runRepository;
        this.leadRepository = leadRepository;
        this.leadService = leadService;
    }

    /** Entry point for the "Run now" action and for event triggers. */
    @Async("leadScoringExecutor")
    public void runBot(Long organizationId, Long botId, String botName, String triggeredBy) {
        RpaBotRun run = startRun(organizationId, botId, triggeredBy);
        try {
            int tasks = switch (botName) {
                case LEAD_ENRICHMENT -> enrichLeads(organizationId);
                case FOLLOW_UP_SEQUENCING -> sequenceFollowUps(organizationId);
                case CASE_ROUTING -> 0; // routing happens on case creation; nothing to sweep
                default -> throw new IllegalStateException("No executable handler for bot \"" + botName + "\"");
            };
            finishRun(run, tasks, describe(botName, tasks));
        } catch (Exception e) {
            failRun(run, e);
        }
    }

    /** Hourly follow-up sweep across every organization that has the bot
     *  registered — the scheduled counterpart to the manual trigger above. */
    @Scheduled(cron = "0 0 * * * *")
    public void hourlyFollowUpSweep() {
        for (RpaBot bot : botRepository.findByName(FOLLOW_UP_SEQUENCING)) {
            runBot(bot.getOrganizationId(), bot.getId(), FOLLOW_UP_SEQUENCING, "schedule");
        }
    }

    /** Scores any lead that has no AI score yet — the catch-up path for rows
     *  created while the model was unavailable.
     *  Not @Transactional: it is self-invoked from runBot, so the proxy is
     *  bypassed and the annotation would have no effect. Each repository call
     *  runs in its own transaction, which is all these reads need. */
    private int enrichLeads(Long organizationId) {
        List<Lead> unscored = leadRepository.findByOrganizationId(organizationId).stream()
                .filter(l -> l.getAiScore() == null)
                .limit(20)
                .toList();
        unscored.forEach(lead -> leadService.scoreAsync(lead.getId(), organizationId));
        return unscored.size();
    }

    /** Same self-invocation note as enrichLeads. */
    private int sequenceFollowUps(Long organizationId) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(STALE_LEAD_DAYS);
        return (int) leadRepository.findByOrganizationId(organizationId).stream()
                .filter(l -> "NEW".equals(l.getStatus()) || "WARM".equals(l.getStatus()))
                .filter(l -> l.getUpdatedAt() != null && l.getUpdatedAt().isBefore(cutoff))
                .limit(20)
                .count();
    }

    private String describe(String botName, int tasks) {
        return switch (botName) {
            case LEAD_ENRICHMENT -> "Queued scoring for " + tasks + " unscored lead(s)";
            case FOLLOW_UP_SEQUENCING -> "Identified " + tasks + " stale lead(s) needing follow-up";
            case CASE_ROUTING -> "Case routing runs on case creation; nothing to sweep";
            default -> "Completed";
        };
    }

    private RpaBotRun startRun(Long organizationId, Long botId, String triggeredBy) {
        RpaBotRun run = new RpaBotRun();
        run.setOrganizationId(organizationId);
        run.setBotId(botId);
        run.setStatus("RUNNING");
        run.setTriggeredBy(triggeredBy);
        return runRepository.save(run);
    }

    private void finishRun(RpaBotRun run, int tasks, String logs) {
        run.setStatus("SUCCESS");
        run.setTasksCompleted(tasks);
        run.setFinishedAt(OffsetDateTime.now());
        run.setLogs(logs);
        runRepository.save(run);
    }

    private void failRun(RpaBotRun run, Exception e) {
        log.warn("RPA bot run {} failed: {}", run.getId(), e.getMessage());
        run.setStatus("ERROR");
        run.setFinishedAt(OffsetDateTime.now());
        run.setErrorMessage(e.getMessage());
        runRepository.save(run);
    }
}
