package com.techcrm.crm.contact;

import com.techcrm.crm.account.AccountRepository;
import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.contact.ContactDtos.ContactRequest;
import com.techcrm.crm.contact.ContactDtos.ContactResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class ContactService {

    private final ContactRepository contactRepository;
    private final AccountRepository accountRepository;

    public ContactService(ContactRepository contactRepository, AccountRepository accountRepository) {
        this.contactRepository = contactRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public List<ContactResponse> list(AuthenticatedUser caller) {
        return contactRepository.findByOrganizationIdOrderByCreatedAtDesc(caller.organizationId())
                .stream().map(ContactResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ContactResponse get(AuthenticatedUser caller, Long id) {
        return ContactResponse.from(require(caller, id));
    }

    @Transactional
    public ContactResponse create(AuthenticatedUser caller, ContactRequest request) {
        Contact contact = new Contact();
        contact.setOrganizationId(caller.organizationId());
        apply(caller, contact, request);
        return ContactResponse.from(contactRepository.save(contact));
    }

    @Transactional
    public ContactResponse update(AuthenticatedUser caller, Long id, ContactRequest request) {
        Contact contact = require(caller, id);
        apply(caller, contact, request);
        return ContactResponse.from(contactRepository.save(contact));
    }

    @Transactional
    public void delete(AuthenticatedUser caller, Long id) {
        contactRepository.delete(require(caller, id));
    }

    private Contact require(AuthenticatedUser caller, Long id) {
        return contactRepository.findByIdAndOrganizationId(id, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found"));
    }

    private void apply(AuthenticatedUser caller, Contact contact, ContactRequest request) {
        // Verified against the caller's own org so a contact can't be attached
        // to another tenant's account by guessing an id.
        accountRepository.findByIdAndOrganizationId(request.accountId(), caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account not found"));

        contact.setAccountId(request.accountId());
        contact.setFullName(request.fullName());
        contact.setJobTitle(request.jobTitle());
        contact.setEmail(request.email());
        contact.setPhone(request.phone());
        contact.setRole(request.role());
        if (request.isPrimary() != null) contact.setPrimary(request.isPrimary());
        if (request.emailNotifications() != null) contact.setEmailNotifications(request.emailNotifications());
        if (request.smsNotifications() != null) contact.setSmsNotifications(request.smsNotifications());
    }
}
