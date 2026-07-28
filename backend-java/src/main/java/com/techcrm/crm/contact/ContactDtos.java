package com.techcrm.crm.contact;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

public final class ContactDtos {

    private ContactDtos() {
    }

    public record ContactRequest(
            @NotNull Long accountId,
            @NotBlank String fullName,
            String jobTitle,
            @Email String email,
            String phone,
            String role,
            Boolean isPrimary,
            Boolean emailNotifications,
            Boolean smsNotifications
    ) {
    }

    public record ContactResponse(
            String id,
            String accountId,
            String fullName,
            String jobTitle,
            String email,
            String phone,
            String role,
            boolean isPrimary,
            boolean emailNotifications,
            boolean smsNotifications,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public static ContactResponse from(Contact c) {
            return new ContactResponse(
                    String.valueOf(c.getId()),
                    String.valueOf(c.getAccountId()),
                    c.getFullName(),
                    c.getJobTitle(),
                    c.getEmail(),
                    c.getPhone(),
                    c.getRole(),
                    c.isPrimary(),
                    c.isEmailNotifications(),
                    c.isSmsNotifications(),
                    c.getCreatedAt(),
                    c.getUpdatedAt()
            );
        }
    }
}
