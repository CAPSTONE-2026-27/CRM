package com.techcrm.crm.contact;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.contact.ContactDtos.ContactRequest;
import com.techcrm.crm.contact.ContactDtos.ContactResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/contacts")
public class ContactController {

    private final ContactService contactService;

    public ContactController(ContactService contactService) {
        this.contactService = contactService;
    }

    @GetMapping
    public List<ContactResponse> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return contactService.list(caller);
    }

    @GetMapping("/{id}")
    public ContactResponse get(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        return contactService.get(caller, id);
    }

    @PostMapping
    public ResponseEntity<ContactResponse> create(@AuthenticationPrincipal AuthenticatedUser caller,
                                                  @Valid @RequestBody ContactRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(contactService.create(caller, request));
    }

    @PutMapping("/{id}")
    public ContactResponse update(@AuthenticationPrincipal AuthenticatedUser caller,
                                  @PathVariable Long id,
                                  @Valid @RequestBody ContactRequest request) {
        return contactService.update(caller, id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        contactService.delete(caller, id);
        return ResponseEntity.noContent().build();
    }
}
