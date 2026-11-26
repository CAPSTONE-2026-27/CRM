package com.techcrm.crm.marketing.ai;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Loads the module's system prompts from src/main/resources/marketing/prompts.
 *
 * Kept as files rather than Java strings so they can be reviewed, versioned
 * and matched to a fine-tuning dataset on their own. Loaded once at startup:
 * a missing prompt fails the boot, not the first campaign.
 *
 * Both prompts contain "only strict json". Each also opens with its routing
 * marker, which ai/server/main.py matches to send the request through the
 * marketing QLoRA adapter (ai/adapters/marketing). That adapter was trained on
 * these exact files, so rewording one means regenerating the dataset and
 * retraining; losing the marker would silently drop marketing back to the base
 * model, so it fails the boot instead. Neither prompt may contain the
 * lead-scoring or meeting-extraction markers, which would send the request
 * through an adapter trained for a different task.
 */
@Component
public class PromptLibrary {

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

    /** Routing markers, matched case-insensitively by ai/adapters/marketing/prompt_format.py. */
    static final String LEAD_MARKER = "you are the campaign audience analyst";
    static final String CONTENT_MARKER = "you are the campaign copywriter";

    private final String leadEvaluation;
    private final String contentGeneration;

    public PromptLibrary() {
        this.leadEvaluation = load("marketing/prompts/lead-evaluation.system.txt", LEAD_MARKER);
        this.contentGeneration = load("marketing/prompts/content-generation.system.txt", CONTENT_MARKER);
    }

    public String leadEvaluation() {
        return leadEvaluation;
    }

    public String contentGeneration() {
        return contentGeneration;
    }

    /**
     * Prepares one user-supplied value for a prompt: strips control
     * characters, neutralises angle brackets (so a value cannot fake a
     * structural tag) and caps the length. Values are then embedded as JSON
     * strings, and the system prompt tells the model the whole document is
     * data rather than instructions.
     */
    public static String clean(String value, int maxChars) {
        if (value == null) return null;
        String cleaned = CONTROL.matcher(value).replaceAll(" ")
                .replace('<', '‹')
                .replace('>', '›')
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (cleaned.isEmpty()) return null;
        return cleaned.length() <= maxChars ? cleaned : cleaned.substring(0, maxChars);
    }

    private static String load(String path, String marker) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String lower = text.toLowerCase();
            if (!lower.contains("only strict json")) {
                throw new IllegalStateException(path + " must contain the phrase 'only strict JSON'");
            }
            if (!lower.contains(marker)) {
                throw new IllegalStateException(path + " must contain its routing marker '" + marker
                        + "' (see ai/adapters/marketing/prompt_format.py)");
            }
            if (lower.contains("you are a crm lead-scoring assistant")
                    || lower.contains("you are a crm lead qualification analyst")) {
                throw new IllegalStateException(path + " contains another task's routing marker");
            }
            return text;
        } catch (IOException e) {
            throw new IllegalStateException("Missing marketing prompt " + path, e);
        }
    }
}
