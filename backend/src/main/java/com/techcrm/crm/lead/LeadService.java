package com.techcrm.crm.lead;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.common.PagedResponse;
import com.techcrm.crm.user.Role;
import com.techcrm.crm.user.User;
import com.techcrm.crm.user.UserRepository;
import com.techcrm.crm.user.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class LeadService {

    private final LeadRepository leadRepository;
    private final UserRepository userRepository;
    private final AiScoringClient aiScoringClient;

    public LeadService(LeadRepository leadRepository, UserRepository userRepository, AiScoringClient aiScoringClient) {
        this.leadRepository = leadRepository;
        this.userRepository = userRepository;
        this.aiScoringClient = aiScoringClient;
    }

    @Transactional
    public LeadResponse create(AuthenticatedUser caller, LeadRequest request) {
        Lead lead = buildLead(caller.organizationId(), request, caller.userId());
        applyAiScore(lead);
        // Assignment follows qualification, not creation: an unqualified lead
        // landing in a rep's queue is exactly the noise the qualification step
        // exists to remove.
        autoAssignIfQualified(lead, caller.organizationId());
        return toResponse(leadRepository.save(lead));
    }

    /** Saves the lead immediately WITHOUT scoring — for bulk paths (CSV
     *  import) where waiting 40-70s per row synchronously isn't viable.
     *  Pair with {@link #scoreAsync} to fill in the score afterward. */
    @Transactional
    public Lead createUnscored(AuthenticatedUser caller, LeadRequest request) {
        return leadRepository.save(buildLead(caller.organizationId(), request, caller.userId()));
    }

    /** Same as {@link #createUnscored}, for callers with no logged-in user
     *  to scope against — e.g. the IMAP poller, which ingests into a single
     *  fixed, configured organization rather than an authenticated caller's.
     *  createdBy is left null: there is no authenticated caller to attribute it to. */
    @Transactional
    public Lead createUnscoredForOrganization(Long organizationId, LeadRequest request) {
        return leadRepository.save(buildLead(organizationId, request, null));
    }

    /** Runs on a background thread (see AsyncConfig) so it doesn't tie up
     *  an HTTP request thread for the 40-70s the AI call can take. */
    @Async("leadScoringExecutor")
    @Transactional
    public void scoreAsync(Long leadId, Long organizationId) {
        leadRepository.findByIdAndOrganizationId(leadId, organizationId).ifPresent(lead -> {
            applyAiScore(lead);
            autoAssignIfQualified(lead, organizationId);
            leadRepository.save(lead);
        });
    }

    private Lead buildLead(Long organizationId, LeadRequest request, Long createdBy) {
        Lead lead = new Lead();
        applyRequest(lead, request);
        if (lead.getStatus() == null) {
            lead.setStatus("NEW");
        }
        lead.setOrganizationId(organizationId);
        lead.setCreatedBy(createdBy);

        // An explicit assignee is honoured regardless of qualification — whoever
        // named a rep has already made that call themselves.
        if (request.assignedToId() != null && !request.assignedToId().isBlank()) {
            setAssignee(lead, resolveAssignee(organizationId, request.assignedToId()));
        }

        return lead;
    }

    @Transactional(readOnly = true)
    public List<LeadResponse> findAll(AuthenticatedUser caller) {
        List<Lead> leads = isScopedToOwnLeads(caller)
                ? leadRepository.findByOrganizationIdAndAssignedToId(caller.organizationId(), caller.userId())
                : leadRepository.findByOrganizationId(caller.organizationId());
        return leads.stream().map(this::toResponse).toList();
    }

    /** Scope (organizationId always, then assignedToId for SALES_REP/
     *  SUPPORT_AGENT) is ANDed in first, before any user-supplied filter —
     *  same guarantee as {@link #isScopedToOwnLeads}, just composed via
     *  Specification. This is what makes "rep A filters by rep B's
     *  assignedToId" correctly yield an empty page rather than an error or
     *  B's data: AND of assignedToId=A and assignedToId=B is simply never
     *  satisfiable. */
    @Transactional(readOnly = true)
    public PagedResponse<LeadResponse> search(AuthenticatedUser caller, LeadSearchCriteria criteria, Pageable pageable) {
        Page<Lead> page = leadRepository.findAll(buildSpecification(caller, criteria), pageable);
        return PagedResponse.from(page.map(this::toResponse));
    }

    private Specification<Lead> buildSpecification(AuthenticatedUser caller, LeadSearchCriteria criteria) {
        Specification<Lead> spec = Specification.where(LeadSpecifications.organizationId(caller.organizationId()));

        if (isScopedToOwnLeads(caller)) {
            spec = spec.and(LeadSpecifications.assignedToId(caller.userId()));
        }

        if (criteria.q() != null) spec = spec.and(LeadSpecifications.globalSearch(criteria.q()));
        if (criteria.fullName() != null) spec = spec.and(LeadSpecifications.fullNameContains(criteria.fullName()));
        if (criteria.company() != null) spec = spec.and(LeadSpecifications.companyContains(criteria.company()));
        if (criteria.email() != null) spec = spec.and(LeadSpecifications.emailContains(criteria.email()));
        if (criteria.phone() != null) spec = spec.and(LeadSpecifications.phoneContains(criteria.phone()));
        if (criteria.status() != null) spec = spec.and(LeadSpecifications.statusEquals(criteria.status()));
        if (criteria.sourceChannel() != null) spec = spec.and(LeadSpecifications.sourceChannelEquals(criteria.sourceChannel()));
        if (criteria.industry() != null) spec = spec.and(LeadSpecifications.industryEquals(criteria.industry()));
        if (criteria.createdFrom() != null) spec = spec.and(LeadSpecifications.createdFrom(criteria.createdFrom()));
        if (criteria.createdTo() != null) spec = spec.and(LeadSpecifications.createdTo(criteria.createdTo()));

        // assignedToId is intentionally allowed even for a scoped-down caller:
        // ANDed against their own forced assignedToId above, so filtering by
        // someone else's id simply narrows to zero results instead of erroring.
        if (criteria.assignedToId() != null) spec = spec.and(LeadSpecifications.assignedToId(criteria.assignedToId()));

        return spec;
    }

    /**
     * Every lead matching the filters, for CSV export — the same visibility
     * rules as {@link #search}, deliberately reusing its Specification so an
     * export can never widen what a scoped-down caller is allowed to see.
     *
     * Unpaged: an export of "page 1 of 40" is not an export.
     */
    @Transactional(readOnly = true)
    public List<Lead> exportAll(AuthenticatedUser caller, LeadSearchCriteria criteria) {
        return leadRepository.findAll(buildSpecification(caller, criteria),
                Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Transactional(readOnly = true)
    public LeadStatsResponse stats(AuthenticatedUser caller) {
        List<Lead> leads = isScopedToOwnLeads(caller)
                ? leadRepository.findByOrganizationIdAndAssignedToId(caller.organizationId(), caller.userId())
                : leadRepository.findByOrganizationId(caller.organizationId());

        long total = leads.size();
        long scored = leads.stream().filter(l -> l.getAiScore() != null).count();
        long csv = leads.stream().filter(l -> "CSV_IMPORT".equals(l.getCaptureMethod())).count();
        long bot = leads.stream().filter(l -> "RPA_BOT_IMPORT".equals(l.getCaptureMethod())).count();

        return new LeadStatsResponse(total, scored, csv, bot);
    }

    @Transactional(readOnly = true)
    public LeadResponse findById(AuthenticatedUser caller, Long id) {
        return toResponse(getOrThrow(caller, id));
    }

    @Transactional
    public LeadResponse update(AuthenticatedUser caller, Long id, LeadRequest request) {
        Lead lead = getOrThrow(caller, id);
        applyRequest(lead, request);

        if (request.assignedToId() != null && !request.assignedToId().isBlank()) {
            guardAssignmentPermission(caller);
            setAssignee(lead, resolveAssignee(caller.organizationId(), request.assignedToId()));
        }

        applyAiScore(lead);
        return toResponse(leadRepository.save(lead));
    }

    /**
     * Partial update. Every field is optional and only non-null ones are
     * applied, so the caller can change one thing without round-tripping — and
     * without a missing field silently blanking a column.
     *
     * Deliberately does NOT re-score: editing a phone number should not move a
     * lead's temperature, and re-scoring on every keystroke-sized edit would
     * burn a model call each time.
     */
    @Transactional
    public LeadResponse patch(AuthenticatedUser caller, Long id, LeadPatchRequest patch) {
        Lead lead = getOrThrow(caller, id);

        if (patch.fullName() != null) lead.setFullName(patch.fullName());
        if (patch.company() != null) lead.setCompany(patch.company());
        if (patch.industry() != null) lead.setIndustry(patch.industry());
        if (patch.employeeCount() != null) lead.setEmployeeCount(patch.employeeCount());
        if (patch.email() != null) lead.setEmail(patch.email());
        if (patch.phone() != null) lead.setPhone(patch.phone());
        if (patch.product() != null) lead.setProduct(patch.product());
        if (patch.estimatedDealValue() != null) lead.setEstimatedDealValue(patch.estimatedDealValue());
        if (patch.sourceChannel() != null) lead.setSourceChannel(patch.sourceChannel());
        if (patch.notes() != null) lead.setNotes(patch.notes());
        if (patch.status() != null) lead.setStatus(patch.status().trim().toUpperCase());

        if (patch.assignedToId() != null) {
            guardAssignmentPermission(caller);
            if (patch.assignedToId().isBlank()) {
                clearAssignee(lead);
            } else {
                setAssignee(lead, resolveAssignee(caller.organizationId(), patch.assignedToId()));
            }
        }

        if (patch.contactStatus() != null) {
            applyContactStatus(lead, patch.contactStatus(), patch.contactNotes());
        } else if (patch.contactNotes() != null) {
            lead.setContactNotes(patch.contactNotes());
        }

        return toResponse(leadRepository.save(lead));
    }

    /**
     * Flow step 4 — a manager places a qualified lead with a sales executive.
     *
     * Unqualified leads are refused here rather than merely discouraged in the
     * UI: the whole point of the qualification step is that nothing downstream
     * acts on a lead that failed it.
     */
    @Transactional
    public LeadResponse assign(AuthenticatedUser caller, Long id, String assignedToId) {
        guardAssignmentPermission(caller);
        Lead lead = getOrThrow(caller, id);

        if (assignedToId == null || assignedToId.isBlank()) {
            clearAssignee(lead);
            return toResponse(leadRepository.save(lead));
        }

        if ("UNQUALIFIED".equals(lead.getQualificationStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This lead was not qualified. Re-score it before assigning it to a sales executive.");
        }

        setAssignee(lead, resolveAssignee(caller.organizationId(), assignedToId));
        return toResponse(leadRepository.save(lead));
    }

    /** Flow step 5 — the executive records how first contact went. */
    @Transactional
    public LeadResponse updateContactStatus(AuthenticatedUser caller, Long id, String contactStatus, String notes) {
        Lead lead = getOrThrow(caller, id);
        applyContactStatus(lead, contactStatus, notes);
        return toResponse(leadRepository.save(lead));
    }

    private void applyContactStatus(Lead lead, String rawStatus, String notes) {
        String status = rawStatus == null ? "" : rawStatus.trim().toUpperCase().replace(' ', '_');
        if (!CONTACT_STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown contact status: " + rawStatus + ". Expected one of " + CONTACT_STATUSES);
        }
        lead.setContactStatus(status);
        lead.setContactStatusUpdatedAt(OffsetDateTime.now());
        if (notes != null) {
            lead.setContactNotes(notes);
        }
    }

    @Transactional
    public void delete(AuthenticatedUser caller, Long id) {
        leadRepository.delete(getOrThrow(caller, id));
    }

    /** Deletes whichever of the given ids exist and are visible to this
     *  caller; ids that don't exist or belong to another org/aren't
     *  assigned to a scoped-down caller are silently skipped rather than
     *  failing the whole batch — the UI reports how many were removed. */
    @Transactional
    public int bulkDelete(AuthenticatedUser caller, List<Long> ids) {
        int deleted = 0;
        for (Long id : ids) {
            try {
                leadRepository.delete(getOrThrow(caller, id));
                deleted++;
            } catch (ResponseStatusException e) {
                // not found / not visible to this caller — skip
            }
        }
        return deleted;
    }

    /** True if an existing lead in this org looks like the same person,
     *  using name/email/company as signals. Missing data on the INCOMING
     *  side never blocks a match (e.g. email-parsed leads never have a
     *  company — that must not stop them matching a manually-entered
     *  record for the same person that does have one); it only skips that
     *  field's comparison. Email is the strongest signal — if the incoming
     *  request has one, a match on email alone is enough, since it
     *  reliably identifies a person by itself regardless of name/company
     *  variations. Without an email, name+company both matching is
     *  required — name alone is too weak (common names collide) to flag
     *  as a duplicate on its own.
     *  Used by CSV import and the paste-an-email flow — NOT the plain "Add
     *  lead" wizard, where a rep manually re-entering a lead they already
     *  know about shouldn't be blocked. */
    @Transactional(readOnly = true)
    public boolean isDuplicate(Long organizationId, LeadRequest request) {
        boolean hasEmail = request.email() != null && !request.email().isBlank();

        List<Lead> candidates = hasEmail
                ? leadRepository.findByOrganizationIdAndEmailIgnoreCase(organizationId, request.email())
                : leadRepository.findByOrganizationIdAndFullNameIgnoreCase(organizationId, request.fullName());

        if (hasEmail) {
            return !candidates.isEmpty();
        }

        return candidates.stream().anyMatch(existing ->
                nonBlankEqualsIgnoreCase(existing.getCompany(), request.company())
        );
    }

    private boolean nonBlankEqualsIgnoreCase(String a, String b) {
        if (a == null || a.isBlank() || b == null || b.isBlank()) {
            return false;
        }
        return a.trim().equalsIgnoreCase(b.trim());
    }

    // Checks the fields worth flagging when blank: enough to actually
    // reach/identify the lead. Deliberately excludes
    // industry/employeeCount/product, which the plain "Add lead" wizard
    // doesn't even collect today, so flagging them here would be noise
    // rather than a genuine gap.
    public List<String> missingFields(LeadRequest request) {
        List<String> missing = new ArrayList<>();
        if (isBlank(request.company())) missing.add("company");
        if (isBlank(request.email())) missing.add("email");
        if (isBlank(request.phone())) missing.add("phone");
        if (isBlank(request.sourceChannel())) missing.add("sourceChannel");
        return missing;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ---- helpers ----

    private boolean isScopedToOwnLeads(AuthenticatedUser caller) {
        return caller.role() == Role.SALES_REP || caller.role() == Role.SUPPORT_AGENT;
    }

    private Lead getOrThrow(AuthenticatedUser caller, Long id) {
        Lead lead = leadRepository.findByIdAndOrganizationId(id, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found with id " + id));

        if (isScopedToOwnLeads(caller) && !caller.userId().equals(lead.getAssignedToId())) {
            // 404, not 403 — don't confirm existence of leads outside this user's scope.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found with id " + id);
        }

        return lead;
    }

    private Long resolveAssignee(Long organizationId, String rawAssignedToId) {
        Long assigneeId;
        try {
            assigneeId = Long.valueOf(rawAssignedToId);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "assignedToId must be a valid user id");
        }

        userRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(assigneeId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "assignedToId does not belong to this organization"));

        return assigneeId;
    }

    /** Only ADMIN and MANAGER may decide who owns a lead. A rep changing their
     *  own book would make the manager's pipeline view fiction. */
    private void guardAssignmentPermission(AuthenticatedUser caller) {
        if (caller.role() != Role.ADMIN && caller.role() != Role.MANAGER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only an administrator or manager can assign leads");
        }
    }

    private void setAssignee(Lead lead, Long assigneeId) {
        lead.setAssignedToId(assigneeId);
        lead.setAssignedAt(OffsetDateTime.now());
        lead.setAssignmentStatus("ASSIGNED");
    }

    private void clearAssignee(Lead lead) {
        lead.setAssignedToId(null);
        lead.setAssignedAt(null);
        lead.setAssignmentStatus("UNASSIGNED");
    }

    /** Least-busy-agent assignment: pick the SALES_REP in this org currently
     *  carrying the fewest leads. Runs only for a qualified, still-unassigned
     *  lead, and leaves it unassigned if the org has no sales reps yet (e.g. a
     *  brand-new org's first lead). */
    private void autoAssignIfQualified(Lead lead, Long organizationId) {
        if (!"QUALIFIED".equals(lead.getQualificationStatus()) || lead.getAssignedToId() != null) {
            return;
        }

        List<User> reps = userRepository.findByOrganizationIdAndRoleAndStatusAndDeletedAtIsNull(organizationId, Role.SALES_REP, UserStatus.ACTIVE);

        User best = null;
        long bestCount = Long.MAX_VALUE;

        for (User rep : reps) {
            long count = leadRepository.countByAssignedToId(rep.getId());
            if (count < bestCount) {
                bestCount = count;
                best = rep;
            }
        }

        if (best != null) {
            setAssignee(lead, best.getId());
        }
    }

    private static final List<String> STATUS_LABELS = List.of("HOT", "WARM", "COLD");

    /** The vocabulary of flow step 5. Held here rather than as an enum because
     *  it crosses the wire as a string and the frontend owns the labels. */
    static final Set<String> CONTACT_STATUSES = Set.of(
            "NOT_CONTACTED", "MEETING_SCHEDULED", "NO_RESPONSE", "INTERESTED", "NOT_INTERESTED");

    private void applyAiScore(Lead lead) {
        AiScoreResult result = aiScoringClient.score(lead);
        if (result != null) {
            lead.setAiScore(result.score());
            lead.setAiScoreLabel(result.label());
            lead.setAiScoreReason(result.reason());

            lead.setQualificationStatus(result.qualificationStatus());
            lead.setQualificationProbability(result.qualificationProbability());
            lead.setQualificationReasoning(result.qualificationReasoning());

            // Drive the lead's status from the AI's verdict so the "Status"
            // column/filter (Hot/Warm/Cold) actually reflects scoring instead
            // of sitting at "NEW" forever. Anything the model returns that
            // isn't one of these (or no label at all) leaves status untouched.
            // Compared case-insensitively: aiScoreLabel is display text, while
            // status is a fixed uppercase vocabulary the frontend styles on.
            if (result.label() != null) {
                String status = result.label().trim().toUpperCase();
                if (STATUS_LABELS.contains(status)) {
                    lead.setStatus(status);
                }
            }
        }
    }

    private void applyRequest(Lead lead, LeadRequest r) {
        lead.setFullName(r.fullName());
        lead.setCompany(r.company());
        lead.setIndustry(r.industry());
        lead.setEmployeeCount(r.employeeCount());
        lead.setEmail(r.email());
        lead.setPhone(r.phone());
        lead.setProduct(r.product());
        lead.setEstimatedDealValue(r.estimatedDealValue());
        lead.setProductQuantity(r.productQuantity());
        lead.setPurchaseTimeline(r.purchaseTimeline());
        lead.setSourceChannel(r.sourceChannel());
        lead.setCaptureMethod(r.captureMethod());
        lead.setNotes(r.notes());
        if (r.status() != null) lead.setStatus(r.status());
    }

    private LeadResponse toResponse(Lead l) {
        return LeadMapper.toResponse(l);
    }
}
