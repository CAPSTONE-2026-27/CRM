package com.techcrm.crm.lead;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.common.PagedResponse;
import com.techcrm.crm.email.EmailLeadParser;
import com.techcrm.crm.email.PastedEmailRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/leads")
public class LeadController {

    // sort is manually allowlisted rather than trusting raw Spring Sort
    // binding — an unknown/hostile field name (e.g. "passwordHash") falls
    // back silently to the default rather than 500ing, consistent with
    // this codebase's existing permissive-on-bad-input posture.
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "fullName", "company", "aiScore", "status");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    // page/size are bound as plain @RequestParam ints here (not a Pageable
    // argument), so WebConfig's PageableHandlerMethodArgumentResolverCustomizer
    // never runs against them — clamp size manually so ?size=999999 can't
    // return an unbounded result set.
    private static final int MAX_PAGE_SIZE = 100;

    private final LeadService leadService;
    private final LeadImportService leadImportService;
    private final EmailLeadParser emailLeadParser;
    private final LeadConversionService leadConversionService;
    private final LeadConversionBotService conversionBotService;

    public LeadController(LeadService leadService,
                          LeadImportService leadImportService,
                          EmailLeadParser emailLeadParser,
                          LeadConversionService leadConversionService,
                          LeadConversionBotService conversionBotService) {
        this.leadService = leadService;
        this.leadImportService = leadImportService;
        this.emailLeadParser = emailLeadParser;
        this.leadConversionService = leadConversionService;
        this.conversionBotService = conversionBotService;
    }

    @PostMapping
    public ResponseEntity<LeadResponse> create(@AuthenticationPrincipal AuthenticatedUser caller, @Valid @RequestBody LeadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(leadService.create(caller, request));
    }

    /** Manual "paste an email in" flow — parses the pasted from/subject/body
     *  the same way the real IMAP poller would, then runs it through the
     *  exact same synchronous create+score+assign path as a normal lead, so
     *  the caller sees the real result immediately (like the wizard does),
     *  rather than the async fire-and-poll pattern CSV import uses. Handy
     *  for testing/using email-sourced leads before a real mailbox is wired
     *  up (see ImapPollingService). */
    /**
     * Turns a pasted email into lead fields without creating anything.
     *
     * The wizard validates before it creates, and validation needs the fields
     * the email actually carries — which only the parser knows. So the email
     * path parses here, validates what came out, and creates separately,
     * exactly as the form path does. {@link #createFromEmail} remains for
     * callers that want both in one step.
     */
    @PostMapping("/parse-email")
    public LeadRequest parseEmail(@AuthenticationPrincipal AuthenticatedUser caller,
                                  @Valid @RequestBody PastedEmailRequest request) {
        return emailLeadParser.parsePasted(request.from(), request.subject(), request.body());
    }

    @PostMapping("/from-email")
    public ResponseEntity<LeadCreationResult> createFromEmail(@AuthenticationPrincipal AuthenticatedUser caller, @Valid @RequestBody PastedEmailRequest request) {
        LeadRequest leadRequest = emailLeadParser.parsePasted(request.from(), request.subject(), request.body());

        if (leadService.isDuplicate(caller.organizationId(), leadRequest)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A lead with this name, email, and company already exists — this email looks like a duplicate.");
        }

        LeadResponse created = leadService.create(caller, leadRequest);
        List<String> missing = leadService.missingFields(leadRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(new LeadCreationResult(created, missing));
    }

    @PostMapping("/import")
    public LeadImportResult importCsv(@AuthenticationPrincipal AuthenticatedUser caller, @Valid @RequestBody CsvImportRequest request) {
        return leadImportService.importCsv(caller, request.csv());
    }

    @GetMapping
    public PagedResponse<LeadResponse> list(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String fullName,
            @RequestParam(required = false) String company,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long assignedToId,
            @RequestParam(required = false) String sourceChannel,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdTo,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        LeadSearchCriteria criteria = LeadSearchCriteria.of(
                q, fullName, company, email, phone, status, assignedToId, sourceChannel, industry, createdFrom, createdTo);

        int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), clampedSize, resolveSort(sort));
        return leadService.search(caller, criteria, pageable);
    }

    @GetMapping("/stats")
    public LeadStatsResponse stats(@AuthenticationPrincipal AuthenticatedUser caller) {
        return leadService.stats(caller);
    }

    /** Open leads for the pipeline board's Prospecting, Qualification and
     *  Meeting columns. Unpaged and capped; see {@link LeadService#pipelineLeads}. */
    @GetMapping("/pipeline")
    public List<LeadResponse> pipeline(@AuthenticationPrincipal AuthenticatedUser caller) {
        return leadService.pipelineLeads(caller);
    }

    /** Moves an open lead between the board's lead columns. */
    @PatchMapping("/{id}/stage")
    public LeadResponse updateStage(@AuthenticationPrincipal AuthenticatedUser caller,
                                    @PathVariable Long id,
                                    @Valid @RequestBody LeadFlowDtos.StageRequest request) {
        return leadService.updatePipelineStage(caller, id, request.stage());
    }

    @GetMapping("/{id}")
    public LeadResponse getOne(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        return leadService.findById(caller, id);
    }

    @PutMapping("/{id}")
    public LeadResponse update(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id, @Valid @RequestBody LeadRequest request) {
        return leadService.update(caller, id, request);
    }

    /** Partial update — changes only the fields present in the body. */
    @PatchMapping("/{id}")
    public LeadResponse patch(@AuthenticationPrincipal AuthenticatedUser caller,
                              @PathVariable Long id,
                              @Valid @RequestBody LeadPatchRequest request) {
        return leadService.patch(caller, id, request);
    }

    /** Flow step 4 — place a qualified lead with a sales executive. */
    @PostMapping("/{id}/assign")
    public LeadResponse assign(@AuthenticationPrincipal AuthenticatedUser caller,
                               @PathVariable Long id,
                               @Valid @RequestBody LeadFlowDtos.AssignRequest request) {
        return leadService.assign(caller, id, request.assignedToId());
    }

    /**
     * A person's qualification verdict, for a lead the scoring model never
     * reached. Those sit at PENDING, and a lead stuck there is one nobody can
     * act on.
     */
    @PostMapping("/{id}/qualify")
    public LeadResponse qualify(@AuthenticationPrincipal AuthenticatedUser caller,
                                @PathVariable Long id,
                                @Valid @RequestBody LeadFlowDtos.QualifyRequest request) {
        return leadService.qualify(caller, id, request.qualified(), request.reason());
    }

    /** Flow step 5 — record the outcome of first contact. */
    @PostMapping("/{id}/contact-status")
    public LeadResponse contactStatus(@AuthenticationPrincipal AuthenticatedUser caller,
                                      @PathVariable Long id,
                                      @Valid @RequestBody LeadFlowDtos.ContactStatusRequest request) {
        return leadService.updateContactStatus(caller, id, request.contactStatus(), request.contactNotes());
    }

    /**
     * Asks the opportunity bot to convert this lead.
     *
     * <b>202</b> once the automation has been started — not once the
     * opportunity exists. The automation calls {@link #convert} itself a moment
     * later, so the caller refreshes and the deal appears.
     *
     * <b>409</b> when the lead is not ready: already converted, unqualified, or
     * scored as something other than HOT. The message says which.
     */
    @PostMapping("/{id}/convert-via-bot")
    public ResponseEntity<LeadFlowDtos.ConversionResponse> convertViaBot(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable Long id) {

        if (!conversionBotService.isConfigured()) {
            // No automation is wired up on this installation, so the CRM does
            // the converting itself — the readiness check above still applies,
            // and the caller cannot tell the difference beyond the status code.
            conversionBotService.requireReadyToConvert(caller, id);
            return convert(caller, id);
        }

        conversionBotService.requestConversion(caller, id);
        return ResponseEntity.accepted().build();
    }

    /** Flow step 6 — convert a qualified lead into an opportunity. The lead id
     *  is the whole input; no request body is read. */
    @PostMapping("/{id}/convert")
    public ResponseEntity<LeadFlowDtos.ConversionResponse> convert(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable Long id) {

        LeadConversionService.ConversionResult result = leadConversionService.convert(caller, id);

        return ResponseEntity.status(HttpStatus.CREATED).body(new LeadFlowDtos.ConversionResponse(
                String.valueOf(id),
                String.valueOf(result.dealId()),
                result.opportunityId(),
                String.valueOf(result.accountId()),
                result.accountCreated(),
                String.valueOf(result.contactId()),
                result.contactCreated()));
    }

    /**
     * Exports every lead matching the current filters as CSV.
     *
     * Streams straight to the response rather than building a String: an
     * unfiltered export of a large organization is the whole lead database, and
     * holding it twice in memory is how this endpoint would fall over first.
     */
    @GetMapping(value = "/export", produces = "text/csv")
    public void export(@AuthenticationPrincipal AuthenticatedUser caller,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) Long assignedToId,
                       @RequestParam(required = false) String sourceChannel,
                       @RequestParam(required = false) String industry,
                       HttpServletResponse response) throws IOException {

        LeadSearchCriteria criteria = LeadSearchCriteria.of(
                q, null, null, null, null, status, assignedToId, sourceChannel, industry, null, null);

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"leads.csv\"");
        LeadCsvExporter.write(leadService.exportAll(caller, criteria), response.getWriter());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        leadService.delete(caller, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bulk-delete")
    public BulkDeleteResult bulkDelete(@AuthenticationPrincipal AuthenticatedUser caller, @Valid @RequestBody BulkDeleteRequest request) {
        List<Long> ids = request.ids().stream()
                .map(raw -> {
                    try {
                        return Long.valueOf(raw);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return new BulkDeleteResult(leadService.bulkDelete(caller, ids));
    }

    private Sort resolveSort(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_SORT;
        }

        String[] parts = raw.split(",");
        String field = parts[0].trim();
        if (!SORTABLE_FIELDS.contains(field)) {
            return DEFAULT_SORT;
        }

        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        return Sort.by(direction, field);
    }
}
