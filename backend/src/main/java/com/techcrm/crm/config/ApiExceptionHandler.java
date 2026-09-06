package com.techcrm.crm.config;

import com.techcrm.crm.contract.document.ContractDocumentException;
import com.techcrm.crm.contract.signature.DocumensoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("Validation failed");

        return errorBody(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        String message = ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();
        return errorBody(HttpStatus.valueOf(ex.getStatusCode().value()), message);
    }

    // Safety net for constraints enforced at the DB level but not (yet)
    // mirrored as a bean-validation annotation — keeps the client-visible
    // error a clean 400 instead of a raw 500 with a leaked SQL message.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        return errorBody(HttpStatus.BAD_REQUEST, "One of the values provided is invalid or out of range.");
    }

    // Document generation carries docx4j stack traces and LibreOffice stderr in
    // its message. ContractService already converts the ones it raises itself
    // into clean responses; this is the safety net for the paths that don't --
    // a download hitting an unreadable file, most often -- so the detail lands
    // in the log and the caller gets a sentence.
    @ExceptionHandler(ContractDocumentException.class)
    public ResponseEntity<Map<String, Object>> handleContractDocument(ContractDocumentException ex) {
        log.error("Contract document operation failed", ex);
        return errorBody(HttpStatus.INTERNAL_SERVER_ERROR, "The contract document could not be produced or read.");
    }

    // Likewise for the signature provider: its errors can quote request details
    // back at us, and 502 is the honest status for an upstream that failed.
    @ExceptionHandler(DocumensoException.class)
    public ResponseEntity<Map<String, Object>> handleDocumenso(DocumensoException ex) {
        log.error("Documenso call failed", ex);
        return errorBody(HttpStatus.BAD_GATEWAY, "The electronic signature provider could not be reached.");
    }

    private ResponseEntity<Map<String, Object>> errorBody(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
