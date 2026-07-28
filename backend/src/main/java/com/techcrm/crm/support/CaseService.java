package com.techcrm.crm.support;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.support.CaseDtos.CaseRequest;
import com.techcrm.crm.support.CaseDtos.CaseResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@Service
public class CaseService {

    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "ESCALATED", "RESOLVED", "CLOSED");

    private final CaseRepository caseRepository;

    public CaseService(CaseRepository caseRepository) {
        this.caseRepository = caseRepository;
    }

    @Transactional(readOnly = true)
    public List<CaseResponse> list(AuthenticatedUser caller) {
        return caseRepository.findByOrganizationIdOrderByCreatedAtDesc(caller.organizationId())
                .stream().map(CaseResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public CaseResponse get(AuthenticatedUser caller, Long id) {
        return CaseResponse.from(require(caller, id));
    }

    @Transactional
    public CaseResponse create(AuthenticatedUser caller, CaseRequest request) {
        CaseRecord record = new CaseRecord();
        record.setOrganizationId(caller.organizationId());
        // Allocated per organization so numbers read as #1, #2, … per tenant
        // rather than exposing a global sequence. The unique constraint on
        // (organization_id, case_number) is the real guard if two creates race.
        Integer max = caseRepository.findMaxCaseNumber(caller.organizationId());
        record.setCaseNumber(max == null ? 1 : max + 1);
        apply(record, request);
        return CaseResponse.from(caseRepository.save(record));
    }

    @Transactional
    public CaseResponse update(AuthenticatedUser caller, Long id, CaseRequest request) {
        CaseRecord record = require(caller, id);
        apply(record, request);
        return CaseResponse.from(caseRepository.save(record));
    }

    @Transactional
    public void delete(AuthenticatedUser caller, Long id) {
        caseRepository.delete(require(caller, id));
    }

    private CaseRecord require(AuthenticatedUser caller, Long id) {
        return caseRepository.findByIdAndOrganizationId(id, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
    }

    private void apply(CaseRecord record, CaseRequest request) {
        record.setSubject(request.subject());
        record.setSource(request.source());
        record.setSlaDeadline(request.slaDeadline());
        record.setAccountId(request.accountId());
        record.setAssignedToId(request.assignedToId());
        if (request.priority() != null) {
            record.setPriority(validate(request.priority(), PRIORITIES, "case priority"));
        }
        if (request.status() != null) {
            record.setStatus(validate(request.status(), STATUSES, "case status"));
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
