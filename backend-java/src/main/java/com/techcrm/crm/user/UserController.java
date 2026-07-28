package com.techcrm.crm.user;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.user.dto.ResetPasswordRequest;
import com.techcrm.crm.user.dto.ResetPasswordResponse;
import com.techcrm.crm.user.dto.UserRequest;
import com.techcrm.crm.user.dto.UserResponse;
import com.techcrm.crm.user.dto.UserStatusRequest;
import com.techcrm.crm.user.dto.UserUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // Every teammate's role/permissions/department/phone is enumerable
    // through this endpoint — restricted to ADMIN/MANAGER, the two roles
    // that already see the full org roster elsewhere (e.g. lead assignment).
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public List<UserResponse> list(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String status
    ) {
        return userService.search(caller, q, role, department, status);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> create(@AuthenticationPrincipal AuthenticatedUser caller, @Valid @RequestBody UserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(caller, request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse update(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id, @RequestBody UserUpdateRequest request) {
        return userService.update(caller, id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse setStatus(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id, @Valid @RequestBody UserStatusRequest request) {
        return userService.setStatus(caller, id, request);
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResetPasswordResponse resetPassword(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id, @RequestBody(required = false) ResetPasswordRequest request) {
        String supplied = request != null ? request.newPassword() : null;
        return userService.resetPassword(caller, id, supplied);
    }

    /** Soft delete — see UserService.delete Javadoc. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        userService.delete(caller, id);
        return ResponseEntity.noContent().build();
    }
}
