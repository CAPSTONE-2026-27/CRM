package com.techcrm.crm.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHashAndRevokedFalse(String tokenHash);

    // Needed by deactivate/reset-password/soft-delete: a user's currently
    // live refresh token(s) must stop working immediately, even though
    // their still-valid access token keeps working until its own natural
    // expiry (accepted residual risk of stateless JWTs, documented elsewhere).
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.userId = :userId AND r.revoked = false")
    int revokeAllForUser(@Param("userId") Long userId);
}
