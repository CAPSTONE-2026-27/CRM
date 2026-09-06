package com.techcrm.crm.contract.signature;

/**
 * Documenso could not be reached, or refused what we sent it.
 *
 * Carries the provider's own wording for the log; callers translate it into a
 * fixed 502 for the API, so a Documenso error string — which can quote request
 * details — never reaches a CRM client.
 */
public class DocumensoException extends RuntimeException {

    public DocumensoException(String message) {
        super(message);
    }

    public DocumensoException(String message, Throwable cause) {
        super(message, cause);
    }
}
