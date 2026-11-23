package com.techcrm.crm.ai;

/**
 * Names the CRM task a model call belongs to, sent as the {@link #HEADER}
 * request header.
 *
 * The local model server (ai/server/main.py) picks its LoRA adapter from this
 * header. Without it, the server falls back to matching the opening clause of
 * the system prompt, so a reworded prompt silently lands on the wrong model.
 * A header rather than the OpenAI "model" field because ai.base-url may point at
 * a hosted provider, which rejects unknown model names but ignores unknown
 * headers.
 *
 * The values must match KNOWN_TASKS in ai/server/main.py.
 */
public final class AiTask {

    public static final String HEADER = "X-CRM-Task";

    public static final String LEAD_SCORING = "lead-scoring";
    public static final String MEETING_EXTRACTION = "meeting-extraction";
    public static final String MARKETING = "marketing";
    /** The base model, no adapter. */
    public static final String GENERAL = "general";

    private AiTask() {
    }
}
