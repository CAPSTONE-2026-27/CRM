package com.techcrm.crm.audit;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.common.PagedResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit-log")
public class AuditLogController {

    private final AuditLogQueryService auditLogQueryService;

    public AuditLogController(AuditLogQueryService auditLogQueryService) {
        this.auditLogQueryService = auditLogQueryService;
    }

    @GetMapping
    public PagedResponse<AuditLogResponse> list(@AuthenticationPrincipal AuthenticatedUser caller, Pageable pageable) {
        // Wrapped in the project's own envelope rather than returned as a raw
        // Spring Data Page, which leaks pageable/sort internals and would give
        // the frontend "number" where every other endpoint gives "page".
        return PagedResponse.from(auditLogQueryService.search(caller, pageable));
    }
}
