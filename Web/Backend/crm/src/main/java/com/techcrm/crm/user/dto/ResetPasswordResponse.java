package com.techcrm.crm.user.dto;

/** The plaintext temporary password is returned exactly once, here — it is
 *  never logged and never retrievable again after this response. */
public record ResetPasswordResponse(String temporaryPassword) {
}
