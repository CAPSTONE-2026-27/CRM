package com.techcrm.crm.marketing;

import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.marketing.api.MarketingDtos.Criterion;
import com.techcrm.crm.marketing.leads.rules.LeadFacts;
import com.techcrm.crm.marketing.leads.rules.RuleEngine;
import com.techcrm.crm.marketing.leads.rules.RuleEngine.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleEngineTest {

    private static Lead lead(String employees, String status, String timeline, String dealValue) {
        Lead l = new Lead();
        l.setId(7L);
        l.setFullName("Asha Rao");
        l.setEmail("asha@example.com");
        l.setEmployeeCount(employees);
        l.setStatus(status);
        l.setPurchaseTimeline(timeline);
        l.setEstimatedDealValue(dealValue == null ? null : new BigDecimal(dealValue));
        l.setIndustry("Manufacturing");
        return l;
    }

    private static Outcome one(Criterion c, Lead l) {
        return RuleEngine.evaluate(List.of(c), "ALL", LeadFacts.from(l)).combined();
    }

    @ParameterizedTest(name = "{0} employees vs > 100 -> {1}")
    @CsvSource({
            "250, PASS",
            "80, FAIL",
            "201-500, PASS",
            "11-50, FAIL",
            // A band that straddles the threshold can't be decided from the band.
            "51-200, UNKNOWN",
            "500+, PASS",
            "'', UNKNOWN"
    })
    void companySizeBandsAreComparedAsRanges(String employees, Outcome expected) {
        assertThat(one(new Criterion("COMPANY_SIZE", "GT", "100"), lead(employees, "HOT", null, null))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} vs <= 90 days -> {1}")
    @CsvSource({
            "Immediately, PASS",
            "Within 2 Months, PASS",
            "Within 3 Months, PASS",
            "More than 3 Months, FAIL"
    })
    void purchaseTimelineMapsToDays(String timeline, Outcome expected) {
        assertThat(one(new Criterion("PURCHASE_TIMELINE_DAYS", "LTE", "90"), lead("100", "HOT", timeline, null))).isEqualTo(expected);
    }

    @Test
    void interestLevelComesFromLeadTemperature() {
        Criterion high = new Criterion("INTEREST_LEVEL", "EQ", "High");
        assertThat(one(high, lead("100", "HOT", null, null))).isEqualTo(Outcome.PASS);
        assertThat(one(high, lead("100", "WARM", null, null))).isEqualTo(Outcome.FAIL);
        assertThat(one(high, lead("100", "NEW", null, null))).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void dealValueAcceptsRupeeFormatting() {
        Criterion c = new Criterion("ESTIMATED_DEAL_VALUE", "GT", "₹5,00,000");
        assertThat(one(c, lead("100", "HOT", null, "750000"))).isEqualTo(Outcome.PASS);
        assertThat(one(c, lead("100", "HOT", null, "400000"))).isEqualTo(Outcome.FAIL);
        assertThat(one(c, lead("100", "HOT", null, null))).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void allModeFailsOnAnyFailureAndAnyModePassesOnAnyPass() {
        List<Criterion> rules = List.of(
                new Criterion("COMPANY_SIZE", "GT", "100"),
                new Criterion("INTEREST_LEVEL", "EQ", "High"));
        LeadFacts facts = LeadFacts.from(lead("500", "COLD", null, null));
        assertThat(RuleEngine.evaluate(rules, "ALL", facts).combined()).isEqualTo(Outcome.FAIL);
        assertThat(RuleEngine.evaluate(rules, "ANY", facts).combined()).isEqualTo(Outcome.PASS);
    }

    @Test
    void textRulesSupportListsCaseInsensitively() {
        Criterion c = new Criterion("INDUSTRY", "IN", "retail, manufacturing");
        assertThat(one(c, lead("100", "HOT", null, null))).isEqualTo(Outcome.PASS);
    }

    @Test
    void invalidRulesAreRejectedAtSaveTime() {
        assertThatThrownBy(() -> RuleEngine.validate(List.of(new Criterion("COMPANY_SIZE", "CONTAINS", "1"))))
                .hasMessageContaining("can't be used");
        assertThatThrownBy(() -> RuleEngine.validate(List.of(new Criterion("COMPANY_SIZE", "GT", "lots"))))
                .hasMessageContaining("needs a number");
        assertThatThrownBy(() -> RuleEngine.validate(List.of(new Criterion("SALARY", "GT", "1"))))
                .hasMessageContaining("Unknown rule field");
    }

    @Test
    void promptFactsCarryNoPersonalData() {
        String facts = LeadFacts.from(lead("100", "HOT", null, null)).toPromptMap().toString();
        assertThat(facts).doesNotContain("Asha").doesNotContain("asha@example.com").contains("LD7");
    }
}
