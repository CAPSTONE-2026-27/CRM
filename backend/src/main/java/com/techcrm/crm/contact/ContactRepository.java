package com.techcrm.crm.contact;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ContactRepository extends JpaRepository<Contact, Long> {

    List<Contact> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    Optional<Contact> findByIdAndOrganizationId(Long id, Long organizationId);

    /** An account's contacts, primary first then oldest. Written as JPQL rather
     *  than a derived name so the ordering is explicit — contract generation
     *  picks the first of these as the signer, and "whichever the database
     *  returned" would make which human gets emailed non-deterministic. */
    @Query("""
            select c from Contact c
            where c.accountId = :accountId and c.organizationId = :organizationId
            order by c.isPrimary desc, c.id asc
            """)
    List<Contact> findForAccount(@Param("accountId") Long accountId,
                                 @Param("organizationId") Long organizationId);
}
