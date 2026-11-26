package com.techcrm.crm.marketing.ai;

/** The model answered, but the answer failed validation and must not be shown or used. */
public class InvalidAiOutputException extends Exception {

    public InvalidAiOutputException(String message) {
        super(message);
    }
}
