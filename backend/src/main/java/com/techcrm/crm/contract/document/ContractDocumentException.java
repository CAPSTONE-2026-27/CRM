package com.techcrm.crm.contract.document;

/**
 * A document could not be produced or stored.
 *
 * Deliberately not a {@code ResponseStatusException}: these carry the underlying
 * cause (a docx4j stack trace, a LibreOffice exit code, a filesystem error) and
 * must never be rendered straight into an API response. {@code ContractService}
 * catches them, records the reason on the contract row where support can read
 * it, and returns a fixed message to the caller — see
 * {@code ContractApiExceptionHandler}.
 */
public class ContractDocumentException extends RuntimeException {

    public ContractDocumentException(String message) {
        super(message);
    }

    public ContractDocumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
