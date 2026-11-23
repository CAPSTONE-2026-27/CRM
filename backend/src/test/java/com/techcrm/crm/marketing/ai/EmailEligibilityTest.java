package com.techcrm.crm.marketing.ai;

import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.marketing.email.EmailSuppression;
import com.techcrm.crm.marketing.leads.CampaignLead;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Being emailable is a mandatory requirement the model's score can't override. */
class EmailEligibilityTest {

    private static Lead lead(String email) {
        Lead l = new Lead();
        l.setEmail(email);
        return l;
    }

    @Test
    void leadsWithoutAUsableOrPermittedAddressAreBlocked() {
        Map<String, EmailSuppression.Reason> suppressed = Map.of("gone@acme.example", EmailSuppression.Reason.UNSUBSCRIBED);
        assertThat(MarketingAiOrchestrator.emailBlock(lead(null), suppressed)).contains("No email address");
        assertThat(MarketingAiOrchestrator.emailBlock(lead("not-an-email"), suppressed)).contains("not valid");
        assertThat(MarketingAiOrchestrator.emailBlock(lead("Gone@Acme.example"), suppressed)).contains("unsubscribed");
        assertThat(MarketingAiOrchestrator.emailBlock(lead("ok@acme.example"), suppressed)).isNull();
    }

    @Test
    void blockedLeadIsNotEligibleAndCarriesNoAiScore() {
        CampaignLead row = new CampaignLead();
        MarketingAiOrchestrator.applyEmailBlock(row, "No email address on the lead");
        assertThat(row.getAiEligibility()).isEqualTo(CampaignLead.Eligibility.NOT_ELIGIBLE);
        assertThat(row.getAiScore()).isNull();
        assertThat(row.getEvaluationSource()).isEqualTo(CampaignLead.Source.EMAIL_CHECK);
        assertThat(row.getNegativeFactors()).contains("Mandatory: No email address on the lead");
    }

    @Test
    void newAbElementsAreSpelledOutForTheModel() {
        assertThat(MarketingAiOrchestrator.describeElement(com.techcrm.crm.marketing.abtest.AbTest.Element.EMAIL_SUBJECT))
                .isEqualTo("EMAIL_SUBJECT");
        assertThat(MarketingAiOrchestrator.describeElement(com.techcrm.crm.marketing.abtest.AbTest.Element.PREVIEW_TEXT))
                .contains("preheader");
    }
}
