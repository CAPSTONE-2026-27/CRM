package com.techcrm.crm.support;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.support.CaseDtos.CaseRequest;
import com.techcrm.crm.support.CaseDtos.CaseResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private final CaseService caseService;

    public CaseController(CaseService caseService) {
        this.caseService = caseService;
    }

    @GetMapping
    public List<CaseResponse> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return caseService.list(caller);
    }

    @GetMapping("/{id}")
    public CaseResponse get(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        return caseService.get(caller, id);
    }

    @PostMapping
    public ResponseEntity<CaseResponse> create(@AuthenticationPrincipal AuthenticatedUser caller,
                                               @Valid @RequestBody CaseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(caseService.create(caller, request));
    }

    @PutMapping("/{id}")
    public CaseResponse update(@AuthenticationPrincipal AuthenticatedUser caller,
                               @PathVariable Long id,
                               @Valid @RequestBody CaseRequest request) {
        return caseService.update(caller, id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        caseService.delete(caller, id);
        return ResponseEntity.noContent().build();
    }
}
