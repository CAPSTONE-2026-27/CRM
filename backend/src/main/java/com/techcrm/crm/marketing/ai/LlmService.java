package com.techcrm.crm.marketing.ai;

import java.time.OffsetDateTime;

/**
 * The module's only doorway to a language model. Pipelines depend on this
 * interface, never on a transport, so moving from the shared Llama server to a
 * dedicated fine-tuned marketing adapter (or any other serving stack) means
 * one new implementation and no pipeline changes.
 */
public interface LlmService {

    /**
     * @param task         short label for logs and metrics ("lead-evaluation"); never the prompt itself
     * @param systemPrompt fixed instructions for the task
     * @param userPrompt   the structured data for this call
     */
    record LlmRequest(String task, String systemPrompt, String userPrompt, double temperature, int maxTokens) {
    }

    record LlmReply(String content, String model) {
    }

    /** The model could not be reached, refused, or is not permitted to receive this data. */
    class LlmUnavailableException extends Exception {
        public LlmUnavailableException(String message) {
            super(message);
        }

        public LlmUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    LlmReply complete(LlmRequest request) throws LlmUnavailableException;

    String modelName();

    /** True when the endpoint is on this machine or a private network. */
    boolean selfHosted();

    boolean externalProviderAllowed();

    /** Null until the first call. */
    Boolean lastCallSucceeded();

    OffsetDateTime lastCallAt();
}
