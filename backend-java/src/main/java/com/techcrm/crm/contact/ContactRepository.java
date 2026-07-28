package com.techcrm.crm.contact;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContactRepository extends JpaRepository<Contact, Long> {

    List<Contact> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    Optional<Contact> findByIdAndOrganizationId(Long id, Long organizationId);
}
