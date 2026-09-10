package com.techcrm.crm.user.dto;

import jakarta.validation.constraints.Size;

/**
 * What a user may change about their own profile.
 *
 * Deliberately NOT {@link UserUpdateRequest}, which also carries {@code role}
 * and {@code permissions}. That one backs the admin-only endpoint; reusing it
 * for self-service would let any authenticated user promote themselves to ADMIN
 * or grant themselves permissions simply by adding a field to the request body.
 * The absence of those two fields here is the entire security control, so do not
 * add them.
 *
 * Every field is optional — a partial update leaves anything null untouched.
 */
public record SelfUpdateRequest(
        @Size(max = 150) String fullName,
        @Size(max = 100) String jobTitle,
        @Size(max = 30) String phone,
        @Size(max = 100) String department,

        /**
         * Data URI or URL for the profile photo.
         *
         * The frontend sends a base64 data URI from FileReader, so this is
         * measured in whole images, not identifiers — hence TEXT in the
         * database (V21) rather than the VARCHAR(500) that OAuth URLs used to
         * fit in. The cap still matters: this column is read back on every
         * {@code /auth/me} call, so an unbounded value would be paid for on
         * every page load. 1.5M characters of base64 is roughly a 1 MB image.
         */
        @Size(max = 1_500_000,
                message = "Profile photo is too large. Please use an image under 1 MB.")
        String avatarUrl
) {
}
