package com.techcrm.crm.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    /** Matches a returning OAuth user on the provider's own identity, which is
     *  stable even if they later change the email on their provider account. */
    Optional<User> findByAuthProviderAndProviderAccountIdAndDeletedAtIsNull(
            String authProvider, String providerAccountId);

    boolean existsByEmailAndDeletedAtIsNull(String email);

    List<User> findByOrganizationIdAndDeletedAtIsNull(Long organizationId);

    List<User> findByOrganizationIdAndRoleAndDeletedAtIsNull(Long organizationId, Role role);

    // Auto-assignment must only ever hand leads to reps who are both
    // present (not soft-deleted) and currently working (ACTIVE) — an
    // INACTIVE rep should stop receiving new leads immediately.
    List<User> findByOrganizationIdAndRoleAndStatusAndDeletedAtIsNull(Long organizationId, Role role, UserStatus status);

    Optional<User> findByIdAndOrganizationIdAndDeletedAtIsNull(Long id, Long organizationId);

    long countByOrganizationIdAndRoleAndDeletedAtIsNull(Long organizationId, Role role);

    long countByOrganizationIdAndRoleAndStatusAndDeletedAtIsNull(Long organizationId, Role role, UserStatus status);

    // Case-insensitive OR match on email or username, active accounts only —
    // login has no org selector, so the identifier must resolve unambiguously
    // across the whole table, not just within one org.
    @Query("SELECT u FROM User u WHERE u.deletedAt IS NULL AND (LOWER(u.email) = LOWER(:identifier) OR LOWER(u.username) = LOWER(:identifier))")
    Optional<User> findActiveByEmailOrUsername(@Param("identifier") String identifier);
}
