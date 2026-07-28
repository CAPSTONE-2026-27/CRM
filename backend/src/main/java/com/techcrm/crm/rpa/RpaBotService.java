package com.techcrm.crm.rpa;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.rpa.RpaDtos.RpaBotRequest;
import com.techcrm.crm.rpa.RpaDtos.RpaBotResponse;
import com.techcrm.crm.rpa.RpaDtos.RpaBotRunResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RpaBotService {

    /** Bots with a real handler in BotExecutionService. Anything else is a
     *  registry entry only, and "Run now" says so rather than silently
     *  recording a run that never does anything. */
    private static final Set<String> EXECUTABLE_BOTS = Set.of(
            BotExecutionService.LEAD_ENRICHMENT,
            BotExecutionService.FOLLOW_UP_SEQUENCING,
            BotExecutionService.CASE_ROUTING);

    private static final Set<String> PLATFORMS = Set.of("UIPATH", "AUTOMATION_ANYWHERE", "BLUE_PRISM");
    private static final Set<String> STATUSES = Set.of("REGISTERED", "SCHEDULED", "RUNNING", "ERROR", "DEPLOYED");

    private final RpaBotRepository botRepository;
    private final RpaBotRunRepository runRepository;
    private final BotExecutionService botExecutionService;

    public RpaBotService(RpaBotRepository botRepository,
                         RpaBotRunRepository runRepository,
                         BotExecutionService botExecutionService) {
        this.botRepository = botRepository;
        this.runRepository = runRepository;
        this.botExecutionService = botExecutionService;
    }

    @Transactional(readOnly = true)
    public List<RpaBotResponse> list(AuthenticatedUser caller) {
        return botRepository.findByOrganizationIdOrderByCreatedAtDesc(caller.organizationId())
                .stream().map(RpaBotResponse::from).toList();
    }

    @Transactional
    public RpaBotResponse create(AuthenticatedUser caller, RpaBotRequest request) {
        RpaBot bot = new RpaBot();
        bot.setOrganizationId(caller.organizationId());
        apply(bot, request);
        return RpaBotResponse.from(botRepository.save(bot));
    }

    @Transactional
    public RpaBotResponse update(AuthenticatedUser caller, Long id, RpaBotRequest request) {
        RpaBot bot = require(caller, id);
        apply(bot, request);
        return RpaBotResponse.from(botRepository.save(bot));
    }

    @Transactional
    public RpaBotResponse deploy(AuthenticatedUser caller, Long id) {
        RpaBot bot = require(caller, id);
        bot.setStatus("DEPLOYED");
        return RpaBotResponse.from(botRepository.save(bot));
    }

    @Transactional
    public void delete(AuthenticatedUser caller, Long id) {
        botRepository.delete(require(caller, id));
    }

    /** Manual "Run now". Rejects bots with no handler instead of queueing work
     *  that would never happen. */
    @Transactional
    public void run(AuthenticatedUser caller, Long id) {
        RpaBot bot = require(caller, id);
        if (!EXECUTABLE_BOTS.contains(bot.getName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No executable handler is registered for bot \"" + bot.getName() + "\"");
        }
        botExecutionService.runBot(caller.organizationId(), bot.getId(), bot.getName(), "manual");
    }

    @Transactional(readOnly = true)
    public List<RpaBotRunResponse> runs(AuthenticatedUser caller, Long botId) {
        List<RpaBotRun> runs = botId == null
                ? runRepository.findTop100ByOrganizationIdOrderByStartedAtDesc(caller.organizationId())
                : runRepository.findTop100ByOrganizationIdAndBotIdOrderByStartedAtDesc(caller.organizationId(), botId);

        // One lookup per distinct bot rather than per run, so a long run list
        // doesn't turn into N queries.
        Map<Long, RpaBot> bots = new HashMap<>();
        for (RpaBot bot : botRepository.findByOrganizationIdOrderByCreatedAtDesc(caller.organizationId())) {
            bots.put(bot.getId(), bot);
        }
        return runs.stream().map(r -> RpaBotRunResponse.from(r, bots.get(r.getBotId()))).toList();
    }

    /** Registers the three built-in bots for a new organization, matching what
     *  signup provisions elsewhere in the product. */
    @Transactional
    public void registerBuiltInBots(Long organizationId) {
        register(organizationId, BotExecutionService.LEAD_ENRICHMENT, "Lead created");
        register(organizationId, BotExecutionService.FOLLOW_UP_SEQUENCING, "Scheduled (hourly)");
        register(organizationId, BotExecutionService.CASE_ROUTING, "Case created");
    }

    private void register(Long organizationId, String name, String triggerSource) {
        if (botRepository.findFirstByOrganizationIdAndName(organizationId, name).isPresent()) {
            return;
        }
        RpaBot bot = new RpaBot();
        bot.setOrganizationId(organizationId);
        bot.setName(name);
        bot.setPlatform("UIPATH");
        bot.setTriggerSource(triggerSource);
        bot.setStatus("REGISTERED");
        botRepository.save(bot);
    }

    private RpaBot require(AuthenticatedUser caller, Long id) {
        return botRepository.findByIdAndOrganizationId(id, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bot not found"));
    }

    private void apply(RpaBot bot, RpaBotRequest request) {
        bot.setName(request.name());
        bot.setTriggerSource(request.triggerSource());
        bot.setCredentialVaultRef(request.credentialVaultRef());
        bot.setEnvironment(request.environment());
        bot.setRegion(request.region());
        bot.setVersion(request.version());
        if (request.botType() != null) bot.setBotType(request.botType().trim().toUpperCase());
        if (request.platform() != null) {
            bot.setPlatform(validate(request.platform(), PLATFORMS, "RPA platform"));
        }
        if (request.status() != null) {
            bot.setStatus(validate(request.status(), STATUSES, "bot status"));
        }
    }

    private String validate(String value, Set<String> allowed, String label) {
        String normalised = value.trim().toUpperCase();
        if (!allowed.contains(normalised)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown " + label + ": " + value);
        }
        return normalised;
    }
}
