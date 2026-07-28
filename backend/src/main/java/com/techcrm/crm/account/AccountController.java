package com.techcrm.crm.account;

import com.techcrm.crm.account.AccountDtos.AccountRequest;
import com.techcrm.crm.account.AccountDtos.AccountResponse;
import com.techcrm.crm.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public List<AccountResponse> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return accountService.list(caller);
    }

    @GetMapping("/{id}")
    public AccountResponse get(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        return accountService.get(caller, id);
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@AuthenticationPrincipal AuthenticatedUser caller,
                                                  @Valid @RequestBody AccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.create(caller, request));
    }

    @PutMapping("/{id}")
    public AccountResponse update(@AuthenticationPrincipal AuthenticatedUser caller,
                                  @PathVariable Long id,
                                  @Valid @RequestBody AccountRequest request) {
        return accountService.update(caller, id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        accountService.delete(caller, id);
        return ResponseEntity.noContent().build();
    }
}
