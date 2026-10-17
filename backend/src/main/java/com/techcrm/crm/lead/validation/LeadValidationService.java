package com.techcrm.crm.lead.validation;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.lead.LeadRepository;
import com.techcrm.crm.lead.LeadRequest;
import com.techcrm.crm.lead.LeadService;
import com.techcrm.crm.lead.validation.LeadValidationDtos.DuplicateCheckRequest;
import com.techcrm.crm.lead.validation.LeadValidationDtos.DuplicateCheckResponse;
import com.techcrm.crm.lead.validation.LeadValidationDtos.ReportVerdictRequest;
import com.techcrm.crm.lead.validation.LeadValidationDtos.StartValidationRequest;
import com.techcrm.crm.lead.validation.LeadValidationDtos.ValidationStatusResponse;
import com.techcrm.crm.lead.validation.LeadValidationDtos.Verdict;
import com.techcrm.crm.sap.SapProcessProperties;
import com.techcrm.crm.sap.SapProcessTriggerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Validates a lead before it is created.
 *
 * The work itself happens in SAP Build Process Automation: required fields,
 * whether the address is reachable, whether the company is real. This class
 * starts that process, follows it, and turns what it reports into a verdict the
 * browser can act on.
 *
 * <b>It degrades open.</b> A validation that cannot run — the platform is
 * unreachable, unconfigured, or simply slow — returns a pass carrying a
 * warning, never a refusal. A representative with a customer on the phone must
 * be able to capture the lead whether or not a cloud service is answering, and
 * an automation that can block lead capture is worse than no automation.
 *
 * <b>A duplicate is a warning, not an error.</b> The Add Lead form has never
 * blocked on duplicates, deliberately: a representative re-entering a lead they
 * already know about should not be stopped. Moving that check into an
 * automation does not change the judgement behind it.
 */
@Service
public class LeadValidationService {

    private static final Logger log = LoggerFactory.getLogger(LeadValidationService.class);

    static final String CAPTURE_EMAIL_PARSING = "EMAIL_PARSING";

    private final LeadValidationRunRepository runRepository;
    private final LeadRepository leadRepository;
    private final LeadService leadService;
    private final SapProcessTriggerClient sap;
    private final SapProcessProperties properties;

    public LeadValidationService(LeadValidationRunRepository runRepository,
                                 LeadRepository leadRepository,
                                 LeadService leadService,
                                 SapProcessTriggerClient sap,
                                 SapProcessProperties properties) {
        this.runRepository = runRepository;
        this.leadRepository = leadRepository;
        this.leadService = leadService;
        this.sap = sap;
        this.properties = properties;
    }

    /* ------------------------------------------------------------- starting */

    /**
     * Records a run and starts the process behind it.
     *
     * Returns as soon as the platform has been asked, not when it answers. The
     * caller polls {@link #status}.
     */
    @Transactional
    public UUID start(AuthenticatedUser caller, StartValidationRequest request) {
        LeadValidationRun run = new LeadValidationRun();
        run.setId(UUID.randomUUID());
        run.setOrganizationId(caller.organizationId());
        run.setStartedBy(caller.userId());
        run.setSubmitted(describe(request));
        runRepository.save(run);

        // This bot specifically, not merely the platform: the contract and
        // proposal bots share the same connection and may be deployed while
        // this one is not.
        if (!properties.isLeadValidationConfigured()) {
            // Not an error: the integration is simply switched off, which is
            // the shipped default. The lead may be created without it.
            finish(run, ValidationState.FAILED,
                    Verdict.ok(List.of("External validation is not configured; the lead was not checked."), null),
                    "SAP process automation is not configured");
            return run.getId();
        }

        // Lower case throughout, because that is how the process declares its
        // inputs and the platform matches them exactly: a camel-cased key is
        // rejected as a missing property rather than quietly normalised.
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("validationid", run.getId().toString());
        context.put("capturemethod", captureMethod(request));
        context.putAll(leadFields(request.lead()));

        String instanceId = sap.startProcess(properties.getLeadValidationDefinitionId(), context);

        if (instanceId == null) {
            finish(run, ValidationState.FAILED,
                    Verdict.ok(List.of("Validation service unavailable; the lead was not checked."), null),
                    "The process could not be started");
            return run.getId();
        }

        run.setInstanceId(instanceId);
        run.setState(ValidationState.RUNNING.name());
        run.setStep(ValidationStep.TRIGGERED.name());
        runRepository.save(run);
        return run.getId();
    }

    /* -------------------------------------------------------------- polling */

    /**
     * The run as it stands, advancing it first if the platform has an answer.
     *
     * Polling the platform here rather than on a schedule keeps the work
     * proportional to the number of people actually waiting: a run nobody is
     * watching costs nothing until the sweep removes it.
     */
    @Transactional
    public ValidationStatusResponse status(AuthenticatedUser caller, UUID validationId) {
        LeadValidationRun run = runRepository
                .findByIdAndOrganizationId(validationId, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Validation run not found"));

        if (!run.isFinished()) {
            advance(run);
        }
        return toResponse(run);
    }

    /**
     * Applies the budget to a run still waiting on its verdict.
     *
     * The automation reports its own verdict when it finishes — see
     * {@link #recordVerdict} — so in the ordinary case a run is already
     * finished before this is reached and there is nothing to do but wait.
     *
     * Asking the platform directly is the fallback, for the run whose
     * automation died before it could report. It is attempted only once the
     * budget has expired, because until then the report may still arrive, and
     * it is allowed to fail: reading an instance needs a privilege on the
     * platform that starting one does not, and a service key without it is a
     * normal configuration rather than a fault.
     */
    private void advance(LeadValidationRun run) {
        if (elapsedSeconds(run) <= properties.getValidationTimeout().toSeconds()) {
            return;
        }

        SapProcessTriggerClient.InstanceStatus status = sap.instanceStatus(run.getInstanceId());
        if (status != null && status.succeeded()) {
            // It finished without telling us. Its own account of the run is
            // still better than giving up on it.
            Map<String, Object> context = sap.instanceContext(run.getInstanceId());
            finish(run, ValidationState.COMPLETED, readVerdict(context), null);
            return;
        }

        if (status != null && status.finished()) {
            finish(run, ValidationState.FAILED,
                    Verdict.ok(List.of("Validation did not complete; the lead was not fully checked."), null),
                    "The process ended as " + status.status());
            return;
        }

        finish(run, ValidationState.TIMED_OUT,
                Verdict.ok(List.of("Validation did not finish in time; the lead was not fully checked."), null),
                "Timed out after " + properties.getValidationTimeout().toSeconds() + "s");
    }

    /**
     * The automation's own account of what it concluded.
     *
     * The verdict arrives here rather than being fetched from the platform.
     * The automation is already calling this API — it signs in, reports
     * progress and asks about duplicates — so one more call carries the answer
     * directly, with no polling delay and without the backend needing
     * permission to read instances it started.
     *
     * Quiet about everything that could go wrong. A report for a run this
     * organization does not have, or one already finished, is ignored: the
     * second is simply a retry, and failing it would make the automation retry
     * again.
     */
    @Transactional
    public void recordVerdict(AuthenticatedUser caller, UUID validationId, ReportVerdictRequest request) {
        runRepository.findByIdAndOrganizationId(validationId, caller.organizationId())
                .filter(run -> !run.isFinished())
                .ifPresent(run -> finish(run, ValidationState.COMPLETED, reportedVerdict(request), null));
    }

    /**
     * Turns the automation's report into a verdict.
     *
     * Deliberately the same shape as a verdict read from a process context:
     * lists arrive as ";"-separated strings, and errors override a report
     * claiming validity, because a report listing errors while calling itself
     * valid is a bug in the automation and the safer reading is the one a
     * person sees.
     */
    private Verdict reportedVerdict(ReportVerdictRequest request) {
        List<String> errors = strings(request.errors());
        List<String> warnings = strings(request.warnings());

        Long duplicateOf = null;
        if (request.duplicateOfLeadId() != null && !request.duplicateOfLeadId().isBlank()) {
            try {
                duplicateOf = Long.valueOf(request.duplicateOfLeadId().trim());
            } catch (NumberFormatException ignored) {
                // A malformed id is not worth failing a lead over.
            }
        }

        boolean valid = Boolean.TRUE.equals(request.valid()) && errors.isEmpty();
        return new Verdict(valid, errors, warnings, duplicateOf);
    }

    /**
     * Turns the process context into a verdict.
     *
     * The automation is expected to write {@code valid}, {@code errors} and
     * {@code warnings}. A completed instance with none of them is reported as a
     * pass with a warning rather than treated as a refusal — an empty context
     * usually means the automation's outputs were never mapped to process
     * context variables, which is a configuration fault on our side and not a
     * reason to reject the customer's lead.
     */
    @SuppressWarnings("unchecked")
    private Verdict readVerdict(Map<String, Object> context) {
        // A context carrying none of the verdict fields is the same as an empty
        // one: the automation ran but nothing it concluded was mapped out of
        // it. Checked by the fields rather than by the map being empty,
        // because the inputs are echoed back and would otherwise make an
        // unmapped context look populated.
        if (context == null
                || (verdictValue(context, "valid") == null
                    && verdictValue(context, "errors") == null
                    && verdictValue(context, "warnings") == null)) {
            log.warn("SAP instance completed without a verdict — are the automation's outputs "
                    + "mapped to process outputs or custom variables?");
            return Verdict.ok(List.of("Validation returned no result; the lead was not fully checked."), null);
        }

        List<String> errors = strings(verdictValue(context, "errors"));
        List<String> warnings = new ArrayList<>(strings(verdictValue(context, "warnings")));

        Long duplicateOf = null;
        Object matched = verdictValue(context, "duplicateOfLeadId");
        if (matched != null && !String.valueOf(matched).isBlank()) {
            try {
                duplicateOf = Long.valueOf(String.valueOf(matched).trim());
            } catch (NumberFormatException ignored) {
                // A malformed id is not worth failing a lead over.
            }
        }

        boolean valid = errors.isEmpty();
        if (verdictValue(context, "valid") instanceof Boolean b) {
            // The automation's own conclusion, where it offers one. Errors still
            // win: a verdict claiming validity while listing errors is a bug in
            // the automation, and the safer reading is the one that surfaces
            // them to a person.
            valid = b && errors.isEmpty();
        }

        return new Verdict(valid, errors, List.copyOf(warnings), duplicateOf);
    }

    /**
     * One verdict field, wherever the platform chose to put it.
     *
     * Where an automation's outputs land depends on what the process editor
     * offered when they were mapped. Some versions bind them to process
     * outputs, which appear at the top of the context; others only allow
     * custom variables, which appear nested under {@code custom}. Both are
     * correct configurations of the same process, so both are read here rather
     * than making the backend depend on which editor built it.
     */
    private static Object verdictValue(Map<String, Object> context, String key) {
        Object direct = context.get(key);
        if (direct != null) {
            return direct;
        }
        return context.get("custom") instanceof Map<?, ?> custom ? custom.get(key) : null;
    }

    /* ------------------------------------------------- progress from the bot */

    /**
     * Records that the automation reached a step it alone can see.
     *
     * Only field validation needs this: it happens inside the automation and
     * makes no call this side could observe. Everything else is inferred from
     * calls the automation already makes.
     *
     * Unknown steps are ignored rather than rejected. A bot deployed from a
     * newer project version may announce a step this build has never heard of,
     * and refusing the call would fail a validation over a label.
     */
    @Transactional
    public void recordProgress(AuthenticatedUser caller, String validationId, String step) {
        ValidationStep parsed = ValidationStep.parse(step);
        recordMilestone(caller, validationId, parsed);
    }

    /* --------------------------------------------- duplicate check callback */

    /**
     * Answers the automation's duplicate question, and records that it asked.
     *
     * The rule stays here rather than in the automation so that the answer is
     * the same whoever asks it — the bot, CSV import, or the paste-an-email
     * flow all reach the same comparison.
     */
    @Transactional
    public DuplicateCheckResponse duplicateCheck(AuthenticatedUser caller, DuplicateCheckRequest request) {
        recordMilestone(caller, request.validationId(), ValidationStep.DUPLICATE_CHECK);

        return leadService.findDuplicate(caller.organizationId(), probeFrom(request))
                .map(match -> new DuplicateCheckResponse(
                        true,
                        String.valueOf(match.lead().getId()),
                        match.field().label(),
                        match.field().blocking()))
                .orElseGet(DuplicateCheckResponse::none);
    }

    /**
     * Notes that the automation reached a step.
     *
     * Progress is observed rather than reported: the automation calls this API
     * part-way through its own run, and that call is the signal. Failing to
     * record it must never fail the call it arrived on, so this is deliberately
     * quiet about problems.
     */
    private void recordMilestone(AuthenticatedUser caller, String validationId, ValidationStep step) {
        if (validationId == null || validationId.isBlank()) {
            return;
        }
        try {
            UUID id = UUID.fromString(validationId.trim());
            runRepository.findByIdAndOrganizationId(id, caller.organizationId()).ifPresent(run -> {
                if (run.isFinished()) {
                    return;
                }
                ValidationStep furthest = ValidationStep.furthest(ValidationStep.parse(run.getStep()), step);
                run.setStep(furthest.name());
                runRepository.save(run);
            });
        } catch (IllegalArgumentException e) {
            log.debug("Ignoring unparseable validationId from the automation: {}", validationId);
        }
    }

    /* ------------------------------------------------------------- sweeping */

    /** Removes runs old enough that nobody is still asking about them. */
    @Transactional
    public int purgeOlderThanOneHour() {
        return runRepository.deleteStartedBefore(OffsetDateTime.now().minusHours(1));
    }

    /* ------------------------------------------------------------- helpers */

    private void finish(LeadValidationRun run, ValidationState state, Verdict verdict, String reason) {
        run.setState(state.name());
        run.setStep(ValidationStep.DONE.name());
        run.setVerdict(verdictMap(verdict));
        run.setFailureReason(reason);
        run.setCompletedAt(OffsetDateTime.now());
        runRepository.save(run);
    }

    private ValidationStatusResponse toResponse(LeadValidationRun run) {
        ValidationStep step = ValidationStep.parse(run.getStep());
        return new ValidationStatusResponse(
                run.getId().toString(),
                run.getState(),
                step.name(),
                step.label(),
                step.percent(),
                verdictFrom(run.getVerdict()),
                run.getFailureReason(),
                run.getStartedAt(),
                run.getCompletedAt());
    }

    private long elapsedSeconds(LeadValidationRun run) {
        return run.getStartedAt() == null
                ? 0
                : java.time.Duration.between(run.getStartedAt(), OffsetDateTime.now()).toSeconds();
    }

    private String captureMethod(StartValidationRequest request) {
        if (hasText(request.captureMethod())) {
            return request.captureMethod().trim().toUpperCase();
        }
        String fromLead = request.lead() == null ? null : request.lead().captureMethod();
        return hasText(fromLead) ? fromLead.trim().toUpperCase() : "WEB_FORM";
    }

    /**
     * The fields the automation validates — every input the Add Lead form marks
     * required, so the automation can check what the form claims to demand.
     *
     * Notes are deliberately excluded. They are free text a representative
     * wrote about a customer, the automation has no rule that reads them, and
     * there is no reason to put them into a cloud platform's process context.
     *
     * Everything is sent as a string, including the two numeric fields. The
     * process context crosses a system boundary into a scripting environment
     * with loose typing, and "" for an absent value is unambiguous there in a
     * way that a null or a zero is not.
     */
    private Map<String, Object> leadFields(LeadRequest lead) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (lead == null) {
            return fields;
        }
        fields.put("fullname", nullToEmpty(lead.fullName()));
        fields.put("company", nullToEmpty(lead.company()));
        fields.put("email", nullToEmpty(lead.email()));
        fields.put("phone", nullToEmpty(lead.phone()));
        fields.put("product", nullToEmpty(lead.product()));
        fields.put("employeecount", nullToEmpty(lead.employeeCount()));
        fields.put("productquantity", numberToString(lead.productQuantity()));
        fields.put("estimateddealvalue", numberToString(lead.estimatedDealValue()));
        return fields;
    }

    private Map<String, Object> describe(StartValidationRequest request) {
        Map<String, Object> submitted = new LinkedHashMap<>(leadFields(request.lead()));
        submitted.put("captureMethod", captureMethod(request));
        return submitted;
    }

    private LeadRequest probeFrom(DuplicateCheckRequest request) {
        return new LeadRequest(
                nullToEmpty(request.fullName()), request.company(), null, null,
                request.email(), request.phone(), null, null, null, null, null, null, null, null, null, null);
    }

    private Map<String, Object> verdictMap(Verdict verdict) {
        if (verdict == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("valid", verdict.valid());
        map.put("errors", verdict.errors());
        map.put("warnings", verdict.warnings());
        map.put("duplicateOfLeadId", verdict.duplicateOfLeadId());
        return map;
    }

    @SuppressWarnings("unchecked")
    private Verdict verdictFrom(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        Long duplicateOf = map.get("duplicateOfLeadId") instanceof Number n ? n.longValue() : null;
        return new Verdict(
                Boolean.TRUE.equals(map.get("valid")),
                strings(map.get("errors")),
                strings(map.get("warnings")),
                duplicateOf);
    }

    /**
     * The messages in a field the automation filled.
     *
     * A list where the platform preserved one, and otherwise a ";"-separated
     * string — which is what the automation builds, because a scripting
     * environment with loose typing produces a string far more reliably than
     * it produces an array. Splitting matters: without it two errors reach a
     * representative as one run-together line.
     */
    private static List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        }
        if (value instanceof String s && !s.isBlank()) {
            return java.util.Arrays.stream(s.split(";"))
                    .map(String::trim)
                    .filter(part -> !part.isEmpty())
                    .toList();
        }
        return List.of();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Absent numbers cross as "" rather than "null" or "0": the automation
     *  tests for emptiness, and a zero quantity is a different statement from
     *  a quantity nobody entered. */
    private static String numberToString(Number value) {
        return value == null ? "" : String.valueOf(value);
    }
}
