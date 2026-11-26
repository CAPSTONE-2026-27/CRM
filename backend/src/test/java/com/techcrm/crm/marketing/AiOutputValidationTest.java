package com.techcrm.crm.marketing;

import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.marketing.ai.ContentOutputValidator;
import com.techcrm.crm.marketing.ai.ContentSource;
import com.techcrm.crm.marketing.ai.InvalidAiOutputException;
import com.techcrm.crm.marketing.ai.LeadEvaluationValidator;
import com.techcrm.crm.marketing.ai.LeadEvaluationValidator.LeadEvaluation;
import com.techcrm.crm.marketing.ai.TemplateContentComposer;
import com.techcrm.crm.marketing.api.MarketingDtos.Criterion;
import com.techcrm.crm.marketing.campaign.CampaignType;
import com.techcrm.crm.marketing.campaign.MarketingCampaign;
import com.techcrm.crm.marketing.content.ContentChannel;
import com.techcrm.crm.marketing.leads.CampaignLead;
import com.techcrm.crm.marketing.leads.CampaignLead.Eligibility;
import com.techcrm.crm.marketing.leads.rules.LeadFacts;
import com.techcrm.crm.marketing.leads.rules.RuleEngine;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiOutputValidationTest {

    @Nested
    class LeadEvaluations {

        @Test
        void acceptsAWellFormedReplyWrappedInProse() throws Exception {
            String reply = "Here you go:\n```json\n{\"lead_id\":\"LD1\",\"score\":87,\"eligibility\":\"eligible\","
                    + "\"confidence\":0.89,\"positive_factors\":[\"High product interest\"],"
                    + "\"negative_factors\":[\"Medium engagement\"],\"reason\":\"Matches the main criteria.\"}\n```";
            LeadEvaluation ev = LeadEvaluationValidator.parse(reply, "LD1");
            assertThat(ev.score()).isEqualTo(87);
            assertThat(ev.eligibility()).isEqualTo(Eligibility.ELIGIBLE);
            assertThat(ev.confidence()).isEqualTo(0.89);
            assertThat(ev.positiveFactors()).containsExactly("High product interest");
        }

        @Test
        void rejectsAReplyAboutADifferentLead() {
            String reply = "{\"lead_id\":\"LD2\",\"score\":50,\"eligibility\":\"eligible\",\"reason\":\"x\"}";
            assertThatThrownBy(() -> LeadEvaluationValidator.parse(reply, "LD1"))
                    .isInstanceOf(InvalidAiOutputException.class).hasMessageContaining("lead_id");
        }

        @Test
        void rejectsOutOfRangeScoresAndUnknownDecisions() {
            assertThatThrownBy(() -> LeadEvaluationValidator.parse(
                    "{\"lead_id\":\"LD1\",\"score\":140,\"eligibility\":\"eligible\",\"reason\":\"x\"}", "LD1"))
                    .hasMessageContaining("between 0 and 100");
            assertThatThrownBy(() -> LeadEvaluationValidator.parse(
                    "{\"lead_id\":\"LD1\",\"score\":\"high\",\"eligibility\":\"eligible\",\"reason\":\"x\"}", "LD1"))
                    .hasMessageContaining("must be a number");
            assertThatThrownBy(() -> LeadEvaluationValidator.parse(
                    "{\"lead_id\":\"LD1\",\"score\":60,\"eligibility\":\"maybe\",\"reason\":\"x\"}", "LD1"))
                    .hasMessageContaining("eligibility");
            assertThatThrownBy(() -> LeadEvaluationValidator.parse("I think this lead is great", "LD1"))
                    .hasMessageContaining("not a JSON object");
        }

        @Test
        void stripsMarkupFromFactors() throws Exception {
            LeadEvaluation ev = LeadEvaluationValidator.parse("{\"lead_id\":\"LD1\",\"score\":60,\"eligibility\":\"eligible\","
                    + "\"positive_factors\":[\"<script>x</script>Good fit\"],\"reason\":\"ok\"}", "LD1");
            assertThat(ev.positiveFactors()).containsExactly("xGood fit");
        }

        @Test
        void ruleGuardOverrulesAnEligibleVerdictWhenARequiredRuleFails() throws Exception {
            Lead lead = new Lead();
            lead.setId(1L);
            lead.setFullName("A");
            lead.setEmployeeCount("20");
            RuleEngine.Evaluation rules = RuleEngine.evaluate(List.of(new Criterion("COMPANY_SIZE", "GT", "100")),
                    "ALL", LeadFacts.from(lead));
            LeadEvaluation ev = new LeadEvaluation("LD1", 91, Eligibility.ELIGIBLE, 0.8, List.of("Strong interest"), List.of(), "Fits");

            CampaignLead row = new CampaignLead();
            Method apply = com.techcrm.crm.marketing.ai.MarketingAiOrchestrator.class.getDeclaredMethod("applyModel",
                    CampaignLead.class, LeadEvaluation.class, String.class, RuleEngine.Evaluation.class);
            apply.setAccessible(true);
            apply.invoke(null, row, ev, "model", rules);

            assertThat(row.getAiEligibility()).isEqualTo(Eligibility.NOT_ELIGIBLE);
            assertThat(row.getRuleGuardApplied()).isTrue();
            // The model's own score and reasoning are preserved, not rewritten.
            assertThat(row.getAiScore()).isEqualTo(91);
            assertThat(row.getAiReason()).isEqualTo("Fits");
            assertThat(row.getNegativeFactors()).contains("Fails campaign rule");
        }
    }

    @Nested
    class ContentGrounding {

        private final String brief = "Product: FlowSuite. Offer: 20% off the first year for orders above ₹5,00,000. "
                + "Contact sales@acme.example. Launches 2026-10-01.";

        private ContentOutputValidator.Result email(String subject, String body) {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("subject", subject);
            f.put("body", body);
            f.put("cta", "Book a demo");
            return ContentOutputValidator.validate(ContentChannel.EMAIL, f, brief);
        }

        @Test
        void acceptsFiguresThatAreInTheBrief() {
            var r = email("FlowSuite: 20% off your first year", "Orders above ₹5,00,000 qualify. Write to sales@acme.example.");
            assertThat(r.errors()).isEmpty();
        }

        @Test
        void rejectsInventedFigures() {
            var r = email("FlowSuite: 30% off", "Save ₹1,00,000 today.");
            assertThat(r.errors()).anyMatch(e -> e.contains("30%")).anyMatch(e -> e.contains("1,00,000"));
        }

        @Test
        void rejectsInventedLinksContactsAndClaims() {
            var r = email("Free trial of FlowSuite", "Guaranteed results. Visit https://flowsuite.io or mail hi@flowsuite.io");
            assertThat(r.errors())
                    .anyMatch(e -> e.contains("'free'"))
                    .anyMatch(e -> e.contains("'guaranteed'"))
                    .anyMatch(e -> e.contains("link"))
                    .anyMatch(e -> e.contains("email address"));
        }

        @Test
        void allowsOnlyTheSupportedPersonalisationTokens() {
            assertThat(email("Hi {{first_name}}", "For {{company}}").errors()).isEmpty();
            assertThat(email("Hi {{first_name}}", "Your rep {{rep_name}}").errors()).anyMatch(e -> e.contains("placeholder"));
            assertThat(email("Hi [Your Name]", "x").errors()).anyMatch(e -> e.contains("placeholder"));
        }

        @Test
        void enforcesRequiredFieldsLengthsAndScripts() {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("subject", "x".repeat(200));
            f.put("body", "<script>alert(1)</script>");
            var r = ContentOutputValidator.validate(ContentChannel.EMAIL, f, brief);
            assertThat(r.errors()).anyMatch(e -> e.contains("limit is 120")).anyMatch(e -> e.contains("script"))
                    .anyMatch(e -> e.contains("'cta' is required"));
        }

        @Test
        void softSuperlativesAreWarningsNotErrors() {
            var r = email("FlowSuite, the industry-leading suite", "Hello");
            assertThat(r.errors()).isEmpty();
            assertThat(r.warnings()).anyMatch(w -> w.contains("industry-leading"));
        }
    }

    @Nested
    class TemplateFallback {

        @ParameterizedTest
        @EnumSource(value = ContentChannel.class, names = "EMAIL")
        void templateOutputAlwaysPassesTheGroundingCheck(ContentChannel channel) {
            MarketingCampaign c = new MarketingCampaign();
            c.setName("Q4 Launch");
            c.setCampaignType(CampaignType.PRODUCT_LAUNCH);
            c.setObjective("Book 40 demos with mid-size manufacturers");
            c.setProductName("FlowSuite");
            c.setProductDescription("FlowSuite automates shop-floor scheduling. It connects to existing ERPs.");
            c.setMainBenefits("Fewer missed deadlines; Real-time capacity view");
            c.setOffer("20% off the first year");
            c.setCallToAction("Book a demo");
            c.setLandingPageUrl("https://flowsuite.example/demo");
            c.setContentConfigJson("{\"senderName\":\"Asha from FlowSuite\"}");
            c.setStartDate(LocalDate.of(2026, 10, 1));
            c.setEndDate(LocalDate.of(2026, 11, 30));
            ContentSource source = ContentSource.from(c);

            Map<String, String> fields = TemplateContentComposer.compose(channel, source);
            var r = ContentOutputValidator.validate(channel, fields, source.groundingText());
            assertThat(r.errors()).as(channel + " template output").isEmpty();
        }
    }
}
