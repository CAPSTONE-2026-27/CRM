package com.techcrm.crm.lead.score;

import com.fasterxml.jackson.databind.JsonNode;
import com.techcrm.crm.ai.AiChatClient;
import com.techcrm.crm.ai.AiChatClient.ChatMessage;
import com.techcrm.crm.ai.AiJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a qualification meeting and reports five business signals. Nothing else.
 *
 * Replaced the former MeetingAnalysisClient (since removed), which asked the
 * model for a summary AND a 0-100 score AND a label in one call. That
 * arrangement has a failure mode with no remedy: the model can return a score
 * its own summary does not support, and afterwards nothing can say which of the
 * two was wrong. Here the model commits to five values in writing and
 * {@link LeadScoreFluctuationEngine} does the arithmetic, so any score can be
 * recomputed from the stored reading and challenged.
 *
 * Returns null when the model is unreachable or its reply unusable. The caller
 * falls back to a neutral reading rather than failing the save — a rep who has
 * just written up a meeting must not lose it because a model was down.
 */
@Component
public class LeadMeetingExtractionClient {

    private static final Logger log = LoggerFactory.getLogger(LeadMeetingExtractionClient.class);

    /**
     * Mirrors meeting_prompt_format.SYSTEM_PROMPT in Llama3_CRM word for word.
     *
     * It must: the adapter was fine-tuned against that exact string, and a
     * reworded prompt at inference is a different prompt than the one trained
     * on. That kind of drift degrades output in a way no metric here would
     * reveal — the replies stay well-formed and quietly get worse.
     */
    private static final String SYSTEM_PROMPT = """
            You are a CRM Lead Qualification Analyst.

            You will be given a sales executive's written notes from a qualification \
            meeting with a customer. Read the notes and extract five business signals.

            You do NOT score the lead. You do not calculate, estimate or mention any \
            score, rating or number. You only report what the notes show.

            Rules:
            - Base every value strictly on the notes. Never infer facts that are not there.
            - The notes will not state these values outright. Read the business meaning: \
            how the customer reacted, who attended, what they asked for, how soon they \
            need it, how much of the product interested them.
            - When the notes are silent on a signal, choose the middle value rather than \
            guessing a favourable or unfavourable one.
            - Use ONLY the allowed values below. Never use synonyms.

            Allowed values:
            - customer_sentiment: one of [Positive, Neutral, Negative]
            - buying_intent: one of [High, Medium, Low]
            - decision_maker_involvement: one of [Present, Indirect, Absent]
            - customer_urgency: one of [High, Medium, Low]
            - product_interest_level: one of [High, Medium, Low]

            Guidance on the harder judgements:
            - decision_maker_involvement is Present when someone who can approve the \
            purchase attended, Indirect when they were represented or consulted but \
            absent, Absent when only evaluators or technical staff attended.
            - buying_intent is High when the customer asked for a proposal, pricing, \
            contract or implementation plan; Medium when they asked for a demo or \
            further evaluation; Low when they were gathering information only.
            - customer_urgency is High when a date, deadline or compelling event was \
            named; Medium when a rough timeframe was given; Low when none was.

            Respond with ONLY the JSON object below. No prose, no markdown fences, no \
            explanation, no score.

            {
              "customer_sentiment": "",
              "buying_intent": "",
              "decision_maker_involvement": "",
              "customer_urgency": "",
              "product_interest_level": ""
            }""";

    private static final String INSTRUCTION =
            "Read the qualification meeting notes and extract the five business signals.";

    private final AiChatClient chatClient;
    private final String modelVersion;

    public LeadMeetingExtractionClient(
            AiChatClient chatClient,
            @Value("${ai.lead-meeting-model:lead-meeting-extraction-v1}") String modelVersion) {
        this.chatClient = chatClient;
        this.modelVersion = modelVersion;
    }

    /**
     * Context for the extraction.
     *
     * The lead profile fields are carried for the record and for the fallback,
     * not fed to the model: the adapter was trained on meeting notes alone, and
     * appending a profile at inference would be input it never saw. The one
     * thing the model must not receive is the current score — it would anchor
     * the reading on a number the notes say nothing about.
     */
    public record ExtractionRequest(
            String fullName,
            String company,
            String industry,
            String companyLocation,
            String employeeCount,
            Integer productQuantity,
            BigDecimal estimatedDealValue,
            String purchaseTimeline,
            String customerType,
            String sourceChannel,
            String leadNotes,
            Integer meetingNumber,
            String meetingDate,
            String meetingTime,
            String meetingNotes
    ) {
    }

    /**
     * @param signals  the five values, already snapped onto the vocabulary
     * @param repaired true when at least one value had to be snapped, so the
     *                 caller can record the reading as less trustworthy
     * @param raw      the reply verbatim, for diagnosing a bad reading later
     */
    public record ExtractionResult(LeadSignals signals, boolean repaired, String raw, String modelVersion) {
    }

    /** Returns null when the model is unreachable or returned no JSON. */
    public ExtractionResult extract(ExtractionRequest request) {
        String reply = chatClient.complete(List.of(
                ChatMessage.system(SYSTEM_PROMPT),
                ChatMessage.user(INSTRUCTION + "\n\n" + request.meetingNotes().trim())));

        JsonNode json = AiJson.extractObject(reply);
        if (json == null) {
            log.warn("Lead meeting extraction returned no usable JSON for meeting {}",
                    request.meetingNumber());
            return null;
        }

        LeadSignals raw = new LeadSignals(
                AiJson.text(json, "customer_sentiment"),
                AiJson.text(json, "buying_intent"),
                AiJson.text(json, "decision_maker_involvement"),
                AiJson.text(json, "customer_urgency"),
                AiJson.text(json, "product_interest_level"));

        LeadSignals normalised = raw.normalise();
        boolean repaired = !raw.equals(normalised);
        if (repaired) {
            // Worth a warning rather than silent repair: a model that needs
            // snapping on every call has drifted, and the only place that shows
            // up is here.
            log.warn("Lead meeting extraction needed repair: {} -> {}", raw, normalised);
        }
        return new ExtractionResult(normalised, repaired, reply, modelVersion);
    }

    /**
     * A keyword reading of the notes, used when the model is unreachable.
     *
     * Deliberately crude and no pretence otherwise — the caller records these
     * as HEURISTIC so a weak reading stays distinguishable from a real one. It
     * exists because a rep who has just written up a meeting should not lose
     * the write-up because a service was down; the notes are the part worth
     * keeping.
     *
     * Every rule below only ever moves a signal away from neutral on an
     * explicit phrase. Silence stays neutral, so an unreadable meeting cannot
     * manufacture a score movement.
     */
    public LeadSignals heuristic(String notes) {
        if (notes == null || notes.isBlank()) return LeadSignals.neutral();
        String text = notes.toLowerCase();

        String sentiment = containsAny(text, "frustrat", "disappoint", "sceptic", "skeptic",
                "unhappy", "pushed back", "not convinced") ? "Negative"
                : containsAny(text, "enthusiast", "excited", "impressed", "positive", "delighted",
                "went well", "very interested") ? "Positive" : "Neutral";

        String intent = containsAny(text, "proposal", "pricing", "quotation", "contract",
                "purchase order", " po ", "procurement", "implementation plan") ? "High"
                : containsAny(text, "demo", "trial", "pilot", "evaluate", "follow-up session") ? "Medium"
                : containsAny(text, "gathering information", "no next step", "browsing",
                "not a priority") ? "Low" : "Medium";

        String decisionMaker = containsAny(text, "ceo", "cfo", "cto", "managing director",
                "vp ", "head of", "director attended", "decision maker attended") ? "Present"
                : containsAny(text, "only technical", "analysts attended", "junior",
                "leadership were not", "nobody from the business") ? "Absent" : "Indirect";

        String urgency = containsAny(text, "deadline", "urgent", "immediately", "this quarter",
                "contract expires", "before the peak", "regulatory") ? "High"
                : containsAny(text, "next year", "no timeline", "not pressing",
                "no rush", "planning round") ? "Low" : "Medium";

        String interest = containsAny(text, "full suite", "all modules", "across several departments",
                "entire platform") ? "High"
                : containsAny(text, "one use case", "limited to", "narrow", "only interested in a")
                ? "Low" : "Medium";

        return new LeadSignals(sentiment, intent, decisionMaker, urgency, interest);
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    /** Only the lead fields the rep actually filled in — kept for the record
     *  and for display, not sent to the model. */
    String describeLead(ExtractionRequest r) {
        List<String> lines = new ArrayList<>();
        lines.add("Contact: " + r.fullName());
        lines.add("Company: " + r.company());
        if (r.industry() != null) lines.add("Industry: " + r.industry());
        if (r.companyLocation() != null) lines.add("Location: " + r.companyLocation());
        if (r.employeeCount() != null) lines.add("Company size: " + r.employeeCount() + " employees");
        if (r.productQuantity() != null) lines.add("Product quantity: " + r.productQuantity());
        if (r.estimatedDealValue() != null) lines.add("Estimated deal value: " + r.estimatedDealValue());
        if (r.purchaseTimeline() != null) lines.add("Purchase timeline: " + r.purchaseTimeline());
        if (r.customerType() != null) lines.add("Customer type: " + r.customerType());
        if (r.sourceChannel() != null) lines.add("Source channel: " + r.sourceChannel());
        if (r.leadNotes() != null) lines.add("Notes: " + r.leadNotes());
        return String.join("\n", lines);
    }
}
