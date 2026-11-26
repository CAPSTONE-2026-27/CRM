package com.techcrm.crm.marketing.email;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmailSuppressionRepository extends JpaRepository<EmailSuppression, Long> {

    Optional<EmailSuppression> findByOrganizationIdAndEmail(Long organizationId, String email);

    @Query("select s from EmailSuppression s where s.organizationId = :org and s.email in :emails")
    List<EmailSuppression> findMatching(@Param("org") Long organizationId, @Param("emails") Collection<String> emails);

    long countByOrganizationId(Long organizationId);
}
