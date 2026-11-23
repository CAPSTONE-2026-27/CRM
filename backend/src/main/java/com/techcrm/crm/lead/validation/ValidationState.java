package com.techcrm.crm.lead.validation;

/** Where a validation run has got to. Mirrors the check constraint in V28. */
public enum ValidationState {

    /** Row created; the external process has not been started yet. */
    PENDING,

    /** The process is running and has an instance id. */
    RUNNING,

    /** A verdict was read from the process. */
    COMPLETED,

    /**
     * The process outlived its budget.
     *
     * Treated as a pass with a warning rather than a refusal: a validation
     * service that is slow or unreachable must not stop a representative
     * capturing a customer. See LeadValidationService.
     */
    TIMED_OUT,

    /** The process could not be started at all — the platform was unreachable,
     *  or rejected the trigger. Also treated as a pass with a warning. */
    FAILED
}
