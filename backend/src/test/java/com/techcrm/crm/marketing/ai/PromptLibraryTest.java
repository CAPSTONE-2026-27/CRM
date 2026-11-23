package com.techcrm.crm.marketing.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The marketing QLoRA adapter (ai/adapters/marketing) is selected by these
 * markers and was trained on these exact prompt files. A prompt that loses its
 * marker would silently fall back to the base model, so boot must fail instead.
 */
class PromptLibraryTest {

    private final PromptLibrary prompts = new PromptLibrary();

    @Test
    void eachPromptCarriesItsOwnRoutingMarker() {
        assertThat(prompts.leadEvaluation().toLowerCase()).contains(PromptLibrary.LEAD_MARKER)
                .doesNotContain(PromptLibrary.CONTENT_MARKER);
        assertThat(prompts.contentGeneration().toLowerCase()).contains(PromptLibrary.CONTENT_MARKER)
                .doesNotContain(PromptLibrary.LEAD_MARKER);
    }

    @Test
    void neitherPromptTriggersAnotherAdapter() {
        for (String prompt : new String[]{prompts.leadEvaluation(), prompts.contentGeneration()}) {
            assertThat(prompt.toLowerCase())
                    .doesNotContain("you are a crm lead-scoring assistant")
                    .doesNotContain("you are a crm lead qualification analyst");
        }
    }
}
