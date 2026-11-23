package com.techcrm.crm.marketing.ai;

import com.techcrm.crm.marketing.ai.LeadEvaluationValidator.LeadEvaluation;
import com.techcrm.crm.marketing.ai.LlmService.LlmReply;
import com.techcrm.crm.marketing.ai.LlmService.LlmRequest;
import com.techcrm.crm.marketing.ai.LlmService.LlmUnavailableException;
import com.techcrm.crm.marketing.api.MarketingDtos.RuleResult;
import com.techcrm.crm.marketing.common.MarketingJson;
import com.techcrm.crm.marketing.leads.rules.LeadFacts;
import com.techcrm.crm.marketing.leads.rules.RuleEngine;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pipeline A, model step: evaluates one lead against one campaign.
 *
 * One call per lead, on purpose. Batching several leads into one prompt is
 * cheaper but lets an 8B model blur leads together, and "each lead gets its
 * own score and its own reasons" is the contract. The reply is validated and
 * gets one corrective retry; a second invalid reply is an error for the
 * caller to handle, never a guess.
 */
@Component
public class LeadEvaluationAi {

    private static final int MAX_TOKENS = 450;

    private final LlmService llm;
    private final PromptLibrary prompts;

    public LeadEvaluationAi(LlmService llm, PromptLibrary prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    /** @param userPrompt the first-attempt prompt (never the retry), stored for retraining */
    public record Result(LeadEvaluation evaluation, String model, String userPrompt) {
    }

    public Result evaluate(Map<String, Object> campaignBrief, LeadFacts facts, RuleEngine.Evaluation rules)
            throws LlmUnavailableException, InvalidAiOutputException {

        String userPrompt = buildUserPrompt(campaignBrief, facts, rules);
        LlmReply reply = llm.complete(new LlmRequest("lead-evaluation", prompts.leadEvaluation(), userPrompt, 0.0, MAX_TOKENS));
        try {
            return new Result(LeadEvaluationValidator.parse(reply.content(), facts.leadRef()), reply.model(), userPrompt);
        } catch (InvalidAiOutputException first) {
            String retryPrompt = userPrompt + "\n\nYour previous reply was rejected: " + first.getMessage()
                    + ". Reply again with only the JSON object described in your instructions.";
            LlmReply retry = llm.complete(new LlmRequest("lead-evaluation-retry", prompts.leadEvaluation(), retryPrompt, 0.0, MAX_TOKENS));
            return new Result(LeadEvaluationValidator.parse(retry.content(), facts.leadRef()), retry.model(), userPrompt);
        }
    }

    static String buildUserPrompt(Map<String, Object> campaignBrief, LeadFacts facts, RuleEngine.Evaluation rules) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("campaign", campaignBrief);

        List<Map<String, Object>> ruleList = rules.results().stream().map(LeadEvaluationAi::ruleForPrompt).toList();
        Map<String, Object> ruleDoc = new LinkedHashMap<>();
        ruleDoc.put("match", rules.match());
        ruleDoc.put("rules", ruleList);
        doc.put("eligibility_rules", ruleDoc);

        doc.put("lead", facts.toPromptMap());
        return MarketingJson.write(doc);
    }

    private static Map<String, Object> ruleForPrompt(RuleResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", r.label());
        m.put("system_check", r.outcome());
        m.put("lead_value", r.actual() == null ? "not recorded" : r.actual());
        return m;
    }
}
