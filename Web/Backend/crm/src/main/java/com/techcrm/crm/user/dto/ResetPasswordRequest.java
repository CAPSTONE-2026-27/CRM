package com.techcrm.crm.user.dto;

/** Optional admin-supplied password; when null/blank a random temporary
 *  password is generated instead. */
public record ResetPasswordRequest(String newPassword) {
}
