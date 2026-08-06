"""
Tests for the lead-scoring bridge in scripts/main.py.

These cover the part of the migration that can silently rot: the mapping from
the CRM's field names onto the trained input shape, and the conversion of the
fine-tune's plain-text output back into the JSON schema AiScoringClient parses.
Nothing here loads the model — every function under test is pure.

Run:
    python -m pytest tests/ -v
"""

import json
import re
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))

from main import (  # noqa: E402
    _employee_count,
    _format_inr,
    _label_for,
    build_lead_input,
    is_lead_scoring,
    lead_scoring_json,
    parse_crm_lead_message,
    wants_json,
)


# The exact string AiScoringClient.describe() builds for a fully-populated lead,
# including the two fields added by migration V17.
CRM_USER_MESSAGE = """Contact: Priya Menon
Company: Pioneer Hospitality K.K.
Industry: Hospitality
Company size: 474 employees
Product interest: CRM Suite
Product quantity: 1244
Estimated deal value: 15409421.00
Purchase timeline: Immediately
Source channel: Website
Notes from sales executive: Ready to buy, only finalizing the vendor choice."""

# A lead whose rep filled in only what the CRM could record before V17. Still
# has to work — most existing rows look like this.
CRM_USER_MESSAGE_PARTIAL = """Contact: Priya Menon
Company: Pioneer Hospitality K.K.
Company size: 474 employees
Estimated deal value: 15409421.00
Notes from sales executive: Ready to buy, only finalizing the vendor choice."""

# The exact shape the fine-tune emits, per data/train.jsonl — all five factors.
MODEL_OUTPUT = """Lead Score: 85/100

Qualification:
Hot

Priority:
High

Reason:
• Employees Count of 474 indicates a mid-sized company, contributing 10 points.
• Product Quantity of 1244 units reflects a large-scale order, contributing 20 points.
• Deal Value of ₹1,54,09,421 places this in a high-value deal tier, contributing 15 points.
• Purchase Timeline of "Immediately" signals maximum urgency, contributing 20 points.
• Customer Requirement — "Ready to buy, only finalizing the vendor choice." — shows confirmed buying intent, contributing 20 points.

Recommended Action:
Assign immediately to a senior sales representative."""

# The same lead as scored before V17, on three factors only.
MODEL_OUTPUT_PARTIAL = """Lead Score: 45/100

Qualification:
Warm

Priority:
Medium

Reason:
• Employees Count of 474 indicates a mid-sized company, contributing 10 points.
• Deal Value of ₹1,54,09,421 places this in a high-value deal tier, contributing 15 points.
• Customer Requirement — "Ready to buy, only finalizing the vendor choice." — shows confirmed buying intent, contributing 20 points.

Recommended Action:
Assign immediately to a senior sales representative."""


class TestRouting:
    def test_lead_scoring_prompt_is_detected(self):
        # Verbatim opening of AiScoringClient.SYSTEM_PROMPT.
        assert is_lead_scoring(
            "You are a CRM lead-scoring assistant. Score the lead from 0-100 based on fit"
        )

    @pytest.mark.parametrize(
        "system_prompt",
        [
            "You are a CRM assistant supporting a sales representative after a customer meeting.",
            "You are a B2B sales analyst. You will be given a sales executive's structured write-up",
            "You are Deal coach, an assistant inside a CRM used by a sales team.",
        ],
    )
    def test_other_modules_route_to_base_model(self, system_prompt):
        assert not is_lead_scoring(system_prompt)

    def test_json_tasks_get_greedy_decoding(self):
        assert wants_json("Respond with ONLY strict JSON, no prose, no markdown fences")
        assert wants_json("Extract the business signals listed below and return them as JSON.")

    def test_coach_chat_does_not(self):
        assert not wants_json("You are Deal coach. Be concise and practical.")


class TestFieldMapping:
    def test_parses_the_crm_describe_block(self):
        fields = parse_crm_lead_message(CRM_USER_MESSAGE)
        assert fields["Company"] == "Pioneer Hospitality K.K."
        assert fields["Company size"] == "474 employees"
        assert fields["Notes from sales executive"].startswith("Ready to buy")

    def test_maps_onto_trained_field_names(self):
        lead_input, present = build_lead_input(parse_crm_lead_message(CRM_USER_MESSAGE))
        assert "Company Name: Pioneer Hospitality K.K." in lead_input
        assert "Employees Count: 474" in lead_input  # " employees" stripped
        assert "Product Quantity: 1244" in lead_input
        assert "Deal Value: ₹1,54,09,421" in lead_input
        assert "Purchase Timeline: Immediately" in lead_input
        assert "Customer Requirement: Ready to buy" in lead_input
        assert present == [
            "Employees Count",
            "Product Quantity",
            "Deal Value",
            "Purchase Timeline",
            "Customer Requirement",
        ]

    def test_field_order_matches_the_training_data(self):
        # All 500 training examples list the fields in this order. Reordering is
        # a needless prompt-shape difference from what the model was tuned on.
        lead_input, _ = build_lead_input(parse_crm_lead_message(CRM_USER_MESSAGE))
        labels = [line.split(":")[0] for line in lead_input.splitlines()]
        assert labels == [
            "Company Name",
            "Employees Count",
            "Product Quantity",
            "Deal Value",
            "Purchase Timeline",
            "Customer Requirement",
        ]

    def test_purchase_timeline_is_passed_through_verbatim(self):
        # TIMELINE_POINTS is an exact-key lookup: reshaping the string here would
        # silently cost the lead its urgency points.
        for timeline in ["Immediately", "Within 15 Days", "More than 3 Months"]:
            message = CRM_USER_MESSAGE.replace("Purchase timeline: Immediately",
                                               f"Purchase timeline: {timeline}")
            lead_input, _ = build_lead_input(parse_crm_lead_message(message))
            assert f"Purchase Timeline: {timeline}" in lead_input

    def test_pre_v17_lead_still_maps(self):
        # Rows created before the new columns existed have no quantity/timeline.
        lead_input, present = build_lead_input(parse_crm_lead_message(CRM_USER_MESSAGE_PARTIAL))
        assert "Product Quantity" not in lead_input
        assert "Purchase Timeline" not in lead_input
        assert present == ["Employees Count", "Deal Value", "Customer Requirement"]

    def test_sparse_lead_reports_fewer_factors(self):
        fields = parse_crm_lead_message("Contact: Sam Roy\nCompany: Acme")
        lead_input, present = build_lead_input(fields)
        assert lead_input == "Company Name: Acme"
        assert present == []

    @pytest.mark.parametrize(
        "raw,expected",
        [
            ("15409421.00", "₹1,54,09,421"),  # lakh/crore grouping, as trained
            ("4633917", "₹46,33,917"),
            ("50000", "₹50,000"),
            ("999", "₹999"),
            ("", ""),
        ],
    )
    def test_deal_value_uses_the_trained_currency_format(self, raw, expected):
        assert _format_inr(raw) == expected

    @pytest.mark.parametrize(
        "raw,expected",
        [
            ("474 employees", "474"),
            ("474", "474"),
            # employee_count is VARCHAR(50), so ranges get typed and imported.
            # Stripping non-digits would read "201-500" as 201500 employees and
            # promote a mid-sized company to the top tier for free.
            ("201-500 employees", "350"),
            ("1-50", "25"),
            ("201 - 500", "350"),
            ("5000+ employees", "5000"),
            ("unknown", None),
            ("", None),
        ],
    )
    def test_employee_count_reads_ranges_as_ranges(self, raw, expected):
        assert _employee_count(raw) == expected


ALL_FACTORS = [
    "Employees Count",
    "Product Quantity",
    "Deal Value",
    "Purchase Timeline",
    "Customer Requirement",
]
PRE_V17_FACTORS = ["Employees Count", "Deal Value", "Customer Requirement"]


def crm_message(employees, quantity, value, timeline, requirement):
    """Build the block AiScoringClient.describe() would send for these inputs."""
    return (
        f"Contact: Test Lead\n"
        f"Company: Acme Test Pvt Ltd\n"
        f"Company size: {employees} employees\n"
        f"Product quantity: {quantity}\n"
        f"Estimated deal value: {value}\n"
        f"Purchase timeline: {timeline}\n"
        f"Notes from sales executive: {requirement}"
    )


class TestScoreBridge:
    def _score(self, output=MODEL_OUTPUT, present=None, message=CRM_USER_MESSAGE):
        present = present or ALL_FACTORS
        lead_input, _ = build_lead_input(parse_crm_lead_message(message))
        return json.loads(lead_scoring_json(output, lead_input, present))

    def test_emits_every_field_aiscoringclient_reads(self):
        result = self._score()
        assert set(result) == {
            "score",
            "label",
            "reason",
            "qualificationStatus",
            "qualificationProbability",
            "qualificationReasoning",
        }

    def test_all_five_factors_make_rescaling_an_identity(self):
        # 10 + 20 + 15 + 20 + 20 = 85 out of the full 100. This is the point of
        # migration V17: with every factor recorded the score needs no rescaling
        # and is directly comparable to the training data again.
        result = self._score()
        assert result["score"] == 85
        assert result["label"] == "Hot"

    def test_score_is_recomputed_from_the_input_not_the_model(self):
        # With all five bullets present, reconcile_output() replaces every point
        # value with the one the input actually earns (prompt_format's tier
        # tables). The model's own arithmetic is overridden entirely — so a reply
        # claiming 20 points for a 474-person company still scores it 10.
        inflated = re.sub(r"contributing \d+ points", "contributing 20 points", MODEL_OUTPUT)
        assert self._score(inflated)["score"] == 85  # not 100

    @pytest.mark.parametrize(
        "employees,quantity,value,timeline,requirement,expected,label",
        [
            # Every factor at its top tier -> a genuine 100/100.
            (8000, 900, "30000000", "Immediately",
             "Budget already approved and ready to move forward.", 100, "Hot"),
            # Every factor at the 10-point tier.
            (500, 60, "3000000", "Within 2 Months",
             "Looking for a CRM to manage customer records.", 50, "Warm"),
            # Every factor at the bottom.
            (20, 2, "50000", "More than 3 Months",
             "Just making a general enquiry.", 0, "Cold"),
        ],
    )
    def test_score_is_deterministic_for_known_inputs(
        self, employees, quantity, value, timeline, requirement, expected, label
    ):
        message = crm_message(employees, quantity, value, timeline, requirement)
        result = self._score(MODEL_OUTPUT, ALL_FACTORS, message)
        assert result["score"] == expected
        assert result["label"] == label

    def test_pre_v17_lead_is_still_rescaled(self):
        # Rows created before the new columns exist have three factors, so their
        # 45-point ceiling of 60 is still stretched onto 0-100 — otherwise no
        # legacy lead could ever read Hot.
        result = self._score(MODEL_OUTPUT_PARTIAL, PRE_V17_FACTORS, CRM_USER_MESSAGE_PARTIAL)
        assert result["score"] == 75  # round(100 * 45 / 60)
        assert result["label"] == "Hot"

    def test_score_stays_in_range(self):
        result = self._score()
        assert 0 <= result["score"] <= 100
        assert 0 <= result["qualificationProbability"] <= 100

    def test_ignores_bullets_for_factors_that_were_not_supplied(self):
        # A pre-V17 lead has no product quantity, but the model still pads its
        # five-bullet template. Counting that bullet would score the lead on a
        # number nobody gave.
        hallucinated = MODEL_OUTPUT_PARTIAL.replace(
            "Recommended Action:",
            "• Product Quantity of 900 units reflects a large-scale order, contributing 20 points.\n\nRecommended Action:",
        )
        result = self._score(hallucinated, PRE_V17_FACTORS, CRM_USER_MESSAGE_PARTIAL)
        assert result["score"] == 75

    def test_fabricated_bullets_do_not_reach_the_reason_text(self):
        # Verbatim from a live run: asked to fill five bullets from three inputs,
        # the model invented a location that was never sent and scored it. The
        # rep reads `reason` as the account's justification, so it must not carry
        # a claim the CRM never made.
        hallucinated = MODEL_OUTPUT_PARTIAL.replace(
            "Recommended Action:",
            "• The company location, Pioneer Hospitality K.K., Tokyo, Japan, "
            "reflects a prominent Japanese company, contributing 20 points.\n\nRecommended Action:",
        )
        result = self._score(hallucinated, PRE_V17_FACTORS, CRM_USER_MESSAGE_PARTIAL)
        assert "Tokyo" not in result["reason"]
        assert "location" not in result["reason"].lower()
        assert result["score"] == 75  # still 45/60, not 65/80
        assert "Employees Count of 474" in result["reason"]
        assert "Deal Value" in result["reason"]
        assert "Customer Requirement" in result["reason"]

    def test_cold_lead_is_unqualified(self):
        message = crm_message(20, 2, "50000", "More than 3 Months",
                              "Just making a general enquiry.")
        result = self._score(MODEL_OUTPUT, ALL_FACTORS, message)
        assert result["qualificationStatus"] == "UNQUALIFIED"

    def test_warm_lead_is_qualified(self):
        message = crm_message(500, 60, "3000000", "Within 2 Months",
                              "Looking for a CRM to manage customer records.")
        result = self._score(MODEL_OUTPUT, ALL_FACTORS, message)
        assert result["qualificationStatus"] == "QUALIFIED"

    def test_urgency_moves_the_score(self):
        # Purchase Timeline is worth 20 points at "Immediately" and 0 at "More
        # than 3 Months" — the whole reason V17 added the column.
        base = dict(employees=500, quantity=60, value="3000000",
                    requirement="Looking for a CRM to manage customer records.")
        urgent = self._score(
            MODEL_OUTPUT, ALL_FACTORS, crm_message(timeline="Immediately", **base))
        patient = self._score(
            MODEL_OUTPUT, ALL_FACTORS, crm_message(timeline="More than 3 Months", **base))
        assert urgent["score"] - patient["score"] == 20

    def test_reason_fits_the_ai_score_reason_column(self):
        # Lead.aiScoreReason is @Column(length = 1000); an over-long value would
        # fail on insert rather than degrade. Five bullets is the worst case.
        assert len(self._score()["reason"]) <= 1000

    def test_complete_lead_carries_no_missing_factor_caveat(self):
        assert "Scored without" not in self._score()["qualificationReasoning"]

    def test_incomplete_lead_says_what_was_missing(self):
        reasoning = self._score(
            MODEL_OUTPUT_PARTIAL, PRE_V17_FACTORS, CRM_USER_MESSAGE_PARTIAL
        )["qualificationReasoning"]
        assert "Product Quantity" in reasoning and "Purchase Timeline" in reasoning

    def test_falls_back_to_the_stated_score_when_bullets_are_unusable(self):
        no_bullets = "Lead Score: 62/100\n\nQualification:\nWarm\n\nPriority:\nMedium"
        assert self._score(no_bullets)["score"] == 62

    def test_unparseable_reply_is_passed_through_for_java_to_reject(self):
        # AiJson.extractObject finds no JSON object, returns null, and
        # AiScoringClient leaves the lead unscored — the existing fallback.
        garbage = "I'm sorry, I can't help with that."
        lead_input, present = build_lead_input(parse_crm_lead_message(CRM_USER_MESSAGE))
        assert lead_scoring_json(garbage, lead_input, present).lstrip()[0] != "{"


class TestLabelBands:
    @pytest.mark.parametrize(
        "score,label",
        [(0, "Cold"), (32, "Cold"), (33, "Warm"), (62, "Warm"), (63, "Hot"), (100, "Hot")],
    )
    def test_bands_match_the_training_data(self, score, label):
        # Boundaries come from prompt_format.QUALIFICATION_BY_SCORE, not restated
        # here, so a dataset reband cannot drift out of sync with the server.
        assert _label_for(score) == label
