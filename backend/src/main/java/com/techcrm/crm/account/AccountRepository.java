package com.techcrm.crm.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    Optional<Account> findByIdAndOrganizationId(Long id, Long organizationId);
}
