package com.techcrm.crm.auth;

import com.techcrm.crm.org.Organization;
import com.techcrm.crm.org.OrganizationRepository;
import com.techcrm.crm.rpa.RpaBotService;
import com.techcrm.crm.user.PermissionDefaults;
import com.techcrm.crm.user.Role;
import com.techcrm.crm.user.User;
import com.techcrm.crm.user.UserRepository;
import com.techcrm.crm.user.UserStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves a Google/Microsoft identity to a CRM user.
 *
 * Three cases, in order:
 *   1. Returning OAuth user — matched on (authProvider, providerAccountId).
 *   2. An existing local account with the same email — the provider is linked
 *      onto it, so password login keeps working and the person doesn't end up
 *      with two accounts.
 *   3. Nobody yet — an organization is created and they become its admin,
 *      matching self-service signup, since OAuth carries no invite context.
 */
@Service
public class OAuthLoginService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final RpaBotService rpaBotService;

    public OAuthLoginService(UserRepository userRepository,
                             OrganizationRepository organizationRepository,
                             RpaBotService rpaBotService) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.rpaBotService = rpaBotService;
    }

    @Transactional
    public User resolve(String provider, String providerAccountId, String email, String displayName, String avatarUrl) {
        User existing = userRepository
                .findByAuthProviderAndProviderAccountIdAndDeletedAtIsNull(provider, providerAccountId)
                .orElse(null);
        if (existing != null) {
            requireActive(existing);
            return existing;
        }

        if (email != null && !email.isBlank()) {
            User byEmail = userRepository.findByEmailAndDeletedAtIsNull(email).orElse(null);
            if (byEmail != null) {
                requireActive(byEmail);
                byEmail.setAuthProvider(provider);
                byEmail.setProviderAccountId(providerAccountId);
                byEmail.setEmailVerified(true);
                if (avatarUrl != null) byEmail.setAvatarUrl(avatarUrl);
                return userRepository.save(byEmail);
            }
        }

        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    provider + " account has no email address — cannot create an account without one");
        }

        Organization organization = new Organization();
        organization.setName((displayName == null || displayName.isBlank() ? email : displayName) + "'s Organization");
        organizationRepository.save(organization);

        User user = new User();
        user.setOrganizationId(organization.getId());
        user.setEmail(email);
        user.setFullName(displayName == null || displayName.isBlank() ? email : displayName);
        // No password: this account can only ever sign in through its provider.
        user.setPasswordHash(null);
        user.setAuthProvider(provider);
        user.setProviderAccountId(providerAccountId);
        user.setAvatarUrl(avatarUrl);
        user.setEmailVerified(true);
        user.setRole(Role.ADMIN);
        user.setPermissions(PermissionDefaults.forRole(Role.ADMIN));
        user.setStatus(UserStatus.ACTIVE);
        user.setMustChangePassword(false);
        userRepository.save(user);

        rpaBotService.registerBuiltInBots(organization.getId());
        return user;
    }

    /** A deactivated account must not be able to get back in via SSO. */
    private void requireActive(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This account has been deactivated. Contact your administrator.");
        }
    }
}
