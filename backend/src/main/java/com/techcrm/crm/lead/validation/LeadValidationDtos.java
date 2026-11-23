package com.techcrm.crm.lead.validation;

import com.techcrm.crm.lead.LeadRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Payloads for the lead validation flow.
 *
 * Three parties exchange these: the browser, which starts a run and then polls
 * it; the automation platform, which asks this API whether a lead is a
 * duplicate part-way through its own run; and the backend, which decides what
 * the answers add up to.
 */
public final class LeadValidationDtos {

    private LeadValidationDtos() {
    }

    /* ------------------------------------------------ browser -> backend */

    /**
     * Body of POST /api/leads/validate/start.
     *
     * The lead is sent but not stored: nothing is created until the caller has
     * a verdict and posts it to the create endpoint itself. Carrying the whole
     * request rather than a handful of fields means the automation sees exactly
     * what will be created, not an approximation of it.
     */
    public record StartValidationRequest(
            @NotNull @Valid LeadRequest lead,

            /**
             * Which capture path this came from. The automation applies a
             * different required-field rule to each: a pasted email carries no
             * company or phone by construction (see EmailLeadParser), so
             * demanding them would refuse every lead from that path.
             */
            @Size(max = 30) String captureMethod
    ) {
    }

    /** Response of POST /api/leads/validate/start — just the handle to poll. */
    public record StartValidationResponse(String validationId) {
    }

    /* ------------------------------------------------ backend -> browser */

    /**
     * Response of GET /api/leads/validate/{id}/status.
     *
     * {@code percent} and {@code label} exist so the browser can render
     * progress without knowing anything about the automation's internals. They
     * are derived from {@code step}, which is the only real signal.
     */
    public record ValidationStatusResponse(
            String validationId,
            String state,
            String step,
            String label,
            int percent,
            Verdict verdict,
            String failureReason,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt
    ) {
    }

    /**
     * What the validation concluded.
     *
     * Errors refuse the lead; warnings do not. The split matters because the
     * Add Lead form has never blocked on a duplicate — a representative
     * re-entering a lead they already know about should not be stopped — so a
     * duplicate arrives here as a warning, however confident the match.
     */
    public record Verdict(
            boolean valid,
            List<String> errors,
            List<String> warnings,
            Long duplicateOfLeadId
    ) {
        public static Verdict ok() {
            return new Verdict(true, List.of(), List.of(), null);
        }

        public static Verdict ok(List<String> warnings, Long duplicateOfLeadId) {
            return new Verdict(true, List.of(), warnings, duplicateOfLeadId);
        }
    }

    /* -------------------------------------------- automation -> backend */

    /**
     * Body of POST /api/leads/validate/{id}/verdict, called by the automation
     * when it finishes.
     *
     * Strings rather than lists because that is what crosses the boundary
     * cleanly: the automation builds them by joining with ";", and a scripting
     * environment with loose typing produces a string more reliably than it
     * produces an array.
     */
    public record ReportVerdictRequest(
            Boolean valid,
            @Size(max = 2000) String errors,
            @Size(max = 2000) String warnings,
            @Size(max = 40) String duplicateOfLeadId
    ) {
    }

    /**
     * Body of POST /api/leads/validate/progress, called by the automation.
     *
     * The step is a {@link ValidationStep} name. An unrecognised one is
     * ignored rather than refused — see the service.
     */
    public record ProgressRequest(
            @NotNull String validationId,
            @Size(max = 40) String step
    ) {
    }

    /**
     * Body of POST /api/leads/duplicate-check, called by the automation.
     *
     * Only the three fields the duplicate rule actually reads. The rule itself
     * stays here rather than in the automation, so the answer is the same
     * whether it is asked by a bot, by CSV import, or by the paste-an-email
     * flow.
     */
    public record DuplicateCheckRequest(
            @Size(max = 150) String fullName,
            @Size(max = 150) String email,
            @Size(max = 150) String company,

            /** Compared on digits only, so formatting differences between
             *  representatives do not hide a match. */
            @Size(max = 30) String phone,

            /**
             * Optional. When the automation passes the run it is working on,
             * this call doubles as a progress signal: the backend learns the
             * bot has reached the duplicate step without the bot having to
             * report progress separately.
             */
            String validationId
    ) {
    }

    /**
     * @param blocking whether this match should refuse the lead.
     *
     * An email address or a phone number identifies one person, so a second
     * lead carrying either is that person entered twice — the automation turns
     * those into errors. A shared name is not: two people genuinely have the
     * same one, so a name+company match is reported as a warning and the
     * representative decides.
     */
    public record DuplicateCheckResponse(
            boolean duplicate,
            String matchedLeadId,
            String matchedOn,
            boolean blocking
    ) {
        public static DuplicateCheckResponse none() {
            return new DuplicateCheckResponse(false, null, null, false);
        }
    }
}
