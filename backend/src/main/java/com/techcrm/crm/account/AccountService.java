package com.techcrm.crm.account;

import com.techcrm.crm.account.AccountDtos.AccountRequest;
import com.techcrm.crm.account.AccountDtos.AccountResponse;
import com.techcrm.crm.auth.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list(AuthenticatedUser caller) {
        return accountRepository.findByOrganizationIdOrderByCreatedAtDesc(caller.organizationId())
                .stream().map(AccountResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse get(AuthenticatedUser caller, Long id) {
        return AccountResponse.from(require(caller, id));
    }

    @Transactional
    public AccountResponse create(AuthenticatedUser caller, AccountRequest request) {
        Account account = new Account();
        account.setOrganizationId(caller.organizationId());
        apply(account, request);
        return AccountResponse.from(accountRepository.save(account));
    }

    @Transactional
    public AccountResponse update(AuthenticatedUser caller, Long id, AccountRequest request) {
        Account account = require(caller, id);
        apply(account, request);
        return AccountResponse.from(accountRepository.save(account));
    }

    @Transactional
    public void delete(AuthenticatedUser caller, Long id) {
        accountRepository.delete(require(caller, id));
    }

    /** Every lookup is organization-scoped, so a caller can never reach
     *  another tenant's record — a wrong-org id is indistinguishable from a
     *  missing one. */
    private Account require(AuthenticatedUser caller, Long id) {
        return accountRepository.findByIdAndOrganizationId(id, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    }

    private void apply(Account account, AccountRequest request) {
        account.setName(request.name());
        account.setIndustry(request.industry());
        account.setAnnualRevenue(request.annualRevenue());
        account.setEmployeeCount(request.employeeCount());
        account.setBillingAddress(request.billingAddress());
        account.setParentAccountId(request.parentAccountId());
        account.setOwnerId(request.ownerId());
        account.setRelationshipValue(request.relationshipValue());
        account.setAiSentimentScore(request.aiSentimentScore());
        if (request.emailIntegrationEnabled() != null) {
            account.setEmailIntegrationEnabled(request.emailIntegrationEnabled());
        }
        if (request.telephonyIntegrationEnabled() != null) {
            account.setTelephonyIntegrationEnabled(request.telephonyIntegrationEnabled());
        }
        if (request.docRepoSyncEnabled() != null) {
            account.setDocRepoSyncEnabled(request.docRepoSyncEnabled());
        }
    }
}
