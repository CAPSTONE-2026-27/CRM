"""
Lead scoring's served accuracy comes from reconcile_output(), not the model.

On the 50 held-out leads of the first dataset the fine-tune's own replies got
the exact score right 58% of the time; after reconcile_output() recomputes each
factor's points from the tier tables in prompt_format.py, every one was right.
The one factor it cannot always recompute is a free-text Customer Requirement,
which is what production sends. Two things would quietly undo that, and each
has a test class here:

  TestTrainingDataAgreement  the tier tables stop matching the data the adapter
                             was trained on (e.g. a regenerated dataset), so the
                             server overwrites correct answers with stale rules.
  TestServedRoutes           a route stops passing the model's reply through
                             the correction before it reaches the CRM.

Run:
    python -m pytest tests/ -v
"""

import json
import re
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

AI_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(AI_ROOT))
sys.path.insert(0, str(AI_ROOT / "server"))

from adapters.lead_scoring import prompt_format as fmt  # noqa: E402

import main  # noqa: E402

TRAIN_JSONL = AI_ROOT / "adapters" / "lead_scoring" / "data" / "train.jsonl"
FACTORS = ["Employees Count", "Product Quantity", "Deal Value", "Purchase Timeline", "Customer Requirement"]

SCORE_RE = re.compile(r"Lead Score:\s*(\d+)/100")
POINTS_RE = re.compile(r"contributing (\d+) points")


def _rows():
    with TRAIN_JSONL.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle]


def _labels(output: str) -> tuple:
    return (
        SCORE_RE.search(output).group(1),
        re.search(r"Qualification:\s*\n?(\w+)", output).group(1),
        re.search(r"Priority:\s*\n?(\w+)", output).group(1),
    )


def _corrupt(output: str) -> str:
    """The worst reply that keeps the format: every number and label wrong."""
    output = POINTS_RE.sub("contributing 0 points", output)
    output = SCORE_RE.sub("Lead Score: 0/100", output)
    output = re.sub(r"(Qualification:\n)\w+", r"\g<1>Cold", output)
    return re.sub(r"(Priority:\n)\w+", r"\g<1>Low", output)


def _requirement(row) -> str:
    return fmt._parse_input_fields(row["input"])["Customer Requirement"]


def _is_template(row) -> bool:
    return fmt._requirement_points(_requirement(row)) is not None


class TestTrainingDataAgreement:
    """The dataset mixes the 20 template requirements, which reconcile_output()
    tiers itself, with free-text sales-rep notes, which only the model can tier
    -- production sends the rep's notes, so that is the judgement it must learn
    (data/generate_dataset.py). The four lookup factors are always recomputed."""

    @pytest.fixture(scope="class")
    @classmethod
    def rows(cls):
        return _rows()

    def test_every_lookup_factor_is_recomputed_to_its_training_points(self, rows):
        mismatches = []
        for index, row in enumerate(rows):
            truth = POINTS_RE.findall(row["output"])
            corrected = POINTS_RE.findall(
                fmt._correct_deterministic_bullets(_corrupt(row["output"]), row["input"]))
            checked = FACTORS if _is_template(row) else FACTORS[:4]
            for factor, want, got in zip(checked, truth, corrected):
                if want != got:
                    mismatches.append((index, factor, want, got))
        assert mismatches == []

    def test_a_fully_wrong_reply_reconciles_to_the_training_label(self, rows):
        # Only where reconcile_output() can tier the requirement itself; a
        # free-text requirement keeps the model's points by design.
        wrong = [
            index for index, row in enumerate(rows)
            if _is_template(row)
            and _labels(fmt.reconcile_output(_corrupt(row["output"]), row["input"])) != _labels(row["output"])
        ]
        assert wrong == []

    def test_template_requirements_are_tiered_as_the_data_labels_them(self, rows):
        templated = [row for row in rows if _is_template(row)]
        assert templated, "no template requirements left -- reconcile_output() would never fire"
        wrong = [index for index, row in enumerate(templated)
                 if fmt._requirement_points(_requirement(row)) != row["requirement_points"]]
        assert wrong == []

    def test_free_text_requirements_carry_their_tier_in_the_target(self, rows):
        free = [row for row in rows if not _is_template(row)]
        assert free, "no free-text requirements -- the model never learns to read a rep's note"
        wrong = [index for index, row in enumerate(free)
                 if f"contributing {row['requirement_points']} points" not in
                 row["output"].split("Customer Requirement")[1].split("\n")[0]]
        assert wrong == []


# What AiScoringClient.describe() sends for a fully populated lead. By the point
# curves: 474 employees 10, 1244 units 20, ₹1,54,09,421 17, Immediately 20,
# "Ready to buy..." 20 -> 87.
CRM_LEAD = """Contact: Priya Menon
Company: Pioneer Hospitality K.K.
Company size: 474 employees
Product quantity: 1244
Estimated deal value: 15409421.00
Purchase timeline: Immediately
Notes from sales executive: Ready to buy, only finalizing the vendor choice."""
RULE_SCORE = 87

LEAD_SYSTEM_PROMPT = (
    "You are a CRM lead-scoring assistant. Score the lead from 0-100 based on fit and intent "
    "signals. Respond with ONLY strict JSON, no prose, no markdown fences."
)

# Right format, wrong arithmetic everywhere: inflated points, a header that
# matches neither, and labels that contradict both.
WRONG_REPLY = """Lead Score: 40/100

Qualification:
Warm

Priority:
Medium

Reason:
• Employees Count of 474 indicates a mid-sized company, contributing 20 points.
• Product Quantity of 1244 units reflects a large-scale order, contributing 5 points.
• Deal Value of ₹1,54,09,421 places this in a high-value deal tier, contributing 0 points.
• Purchase Timeline of "Immediately" signals maximum urgency, contributing 10 points.
• Customer Requirement — "Ready to buy, only finalizing the vendor choice." — shows confirmed buying intent, contributing 5 points.

Recommended Action:
Assign immediately to a senior sales representative."""


class TestServedRoutes:
    @pytest.fixture
    def client(self, monkeypatch):
        monkeypatch.setattr(main, "model", object())
        monkeypatch.setattr(main, "tokenizer", object())
        monkeypatch.setattr(main, "NORMALISE_LEAD_SCORE", True)
        monkeypatch.setattr(main, "generate_text", lambda prompt, **kwargs: WRONG_REPLY)
        return TestClient(main.app)

    def _request(self, **extra):
        return {
            "messages": [
                {"role": "system", "content": LEAD_SYSTEM_PROMPT},
                {"role": "user", "content": CRM_LEAD},
            ],
            **extra,
        }

    def test_blocking_route_serves_the_rule_score_not_the_models(self, client):
        body = client.post("/v1/chat/completions", json=self._request()).json()
        payload = json.loads(body["choices"][0]["message"]["content"])
        assert payload["score"] == RULE_SCORE
        assert payload["label"] == "Hot"

    def test_streaming_route_serves_the_rule_score_too(self, client):
        response = client.post("/v1/chat/completions", json=self._request(stream=True))
        contents = [
            json.loads(line[len("data: "):])["choices"][0]["delta"].get("content")
            for line in response.text.splitlines()
            if line.startswith("data: {")
        ]
        payload = json.loads("".join(c for c in contents if c))
        assert payload["score"] == RULE_SCORE
        assert payload["label"] == "Hot"


class TestFreeTextRequirementClamp:
    """A free-text requirement keeps the model's tier, but only a real tier."""

    LEAD = ("Company Name: Acme\nEmployees Count: 474\nProduct Quantity: 1244\n"
            "Deal Value: ₹1,54,09,421\nPurchase Timeline: Immediately\n"
            "Customer Requirement: CEO confirmed go-ahead, want to start on the 1st")

    def _reply(self, requirement_points: int) -> str:
        return WRONG_REPLY.replace(
            '— "Ready to buy, only finalizing the vendor choice." — shows confirmed buying intent, contributing 5 points.',
            f'— "CEO confirmed go-ahead, want to start on the 1st" — shows confirmed buying intent, '
            f'contributing {requirement_points} points.')

    def _requirement(self, reconciled: str) -> int:
        line = next(l for l in reconciled.splitlines() if l.startswith("• Customer Requirement"))
        return int(POINTS_RE.search(line).group(1))

    def test_out_of_range_points_snap_to_the_top_tier(self):
        assert self._requirement(fmt.reconcile_output(self._reply(25), self.LEAD)) == 20

    def test_off_grid_points_snap_to_the_nearest_tier(self):
        assert self._requirement(fmt.reconcile_output(self._reply(12), self.LEAD)) == 10

    def test_a_valid_tier_is_left_to_the_model(self):
        assert self._requirement(fmt.reconcile_output(self._reply(15), self.LEAD)) == 15

    def test_total_never_exceeds_the_factor_maximum(self):
        # 10 + 20 + 17 + 20 for the lookup factors, plus at most 20.
        assert _labels(fmt.reconcile_output(self._reply(25), self.LEAD))[0] == "87"


class TestContinuousPoints:
    """The numeric factors are continuous, so the score is not stuck on
    multiples of 5 and nearby leads no longer jump a whole tier apart."""

    def test_a_cutoff_is_no_longer_a_cliff(self):
        assert abs(fmt.employees_points(52) - fmt.employees_points(51)) <= 1
        assert abs(fmt.quantity_points(102) - fmt.quantity_points(101)) <= 1
        assert abs(fmt.deal_value_points(998_900) - fmt.deal_value_points(998_899)) <= 1

    def test_different_leads_in_one_old_tier_score_differently(self):
        assert fmt.employees_points(1000) > fmt.employees_points(300)

    def test_points_rise_with_value_and_stay_in_range(self):
        for points in (fmt.employees_points, fmt.quantity_points, fmt.deal_value_points):
            series = [points(v) for v in (1, 10, 100, 1_000, 10_000, 100_000, 10**7, 10**9)]
            assert series == sorted(series)
            assert min(series) >= 0 and max(series) <= 20

    def test_training_scores_are_not_all_multiples_of_five(self):
        scores = [int(SCORE_RE.search(r["output"]).group(1)) for r in _rows()]
        assert sum(s % 5 != 0 for s in scores) > len(scores) / 2
