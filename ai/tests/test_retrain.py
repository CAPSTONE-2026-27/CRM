"""
Tests for the marketing retraining loop: turning review decisions into training
rows (adapters/marketing/data/collect_feedback.py) and deciding whether a
retrained adapter replaces the live one (tools/retrain.py).

No database, GPU or model server needed.
"""

import json
import sys
from pathlib import Path

import pytest

AI_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(AI_ROOT))
sys.path.insert(0, str(AI_ROOT / "tools"))

from adapters.marketing import prompt_format as fmt  # noqa: E402
from adapters.marketing.data import collect_feedback as cf  # noqa: E402
import retrain  # noqa: E402

LEAD_INPUT = fmt.to_java_json({
    "campaign": {"campaign_type": "cross sell", "objective": "Sell Sales Cloud"},
    "eligibility_rules": {"match": "ALL", "rules": [
        {"rule": "Industry is Pharmaceuticals", "system_check": "PASS", "lead_value": "Pharmaceuticals"},
        {"rule": "Company size >= 50", "system_check": "UNKNOWN", "lead_value": "not recorded"},
    ]},
    "lead": {"lead_id": "LD100", "industry": "Pharmaceuticals"},
})


def lead_row(**overrides):
    row = {
        "id": 7, "ai_input": LEAD_INPUT, "ai_score": 72, "ai_eligibility": "ELIGIBLE", "ai_confidence": 0.9,
        "positive_factors": '["Target industry"]', "negative_factors": '["Company size unknown"]',
        "ai_reason": "Fits the campaign's industry.", "approval_status": "APPROVED",
        "override_eligibility": None, "review_note": None,
    }
    row.update(overrides)
    return row


def target_of(example):
    return json.loads(example["messages"][2]["content"])


class TestLeadExamples:
    def test_approved_eligible_lead_is_a_confirmed_answer(self):
        example = cf.lead_example(lead_row())
        target, meta = target_of(example), json.loads(example["meta"])
        assert target["eligibility"] == "eligible" and target["score"] == 72
        assert target["reason"] == "Fits the campaign's industry."
        assert meta["corrected"] is False and meta["source"] == "feedback" and meta["ref"] == "lead:7"
        # The model is taught on exactly what it was sent, with the live system prompt.
        assert example["messages"][1]["content"] == LEAD_INPUT
        assert example["messages"][0]["content"] == fmt.LEAD_SYSTEM_PROMPT

    def test_override_uses_the_human_verdict_note_and_band(self):
        example = cf.lead_example(lead_row(override_eligibility="NOT_ELIGIBLE", approval_status="REJECTED",
                                           review_note="Only existing customers for this offer."))
        target = target_of(example)
        assert target["eligibility"] == "not_eligible"
        assert target["reason"] == "Only existing customers for this offer."
        assert target["score"] == 59  # 72 moved below the eligible band
        assert target["confidence"] == cf.CORRECTED_CONFIDENCE
        assert json.loads(example["meta"])["corrected"] is True

    def test_override_to_eligible_raises_a_low_score_into_band(self):
        example = cf.lead_example(lead_row(ai_score=30, ai_eligibility="NOT_ELIGIBLE",
                                           override_eligibility="ELIGIBLE", review_note="Strategic account."))
        assert target_of(example)["score"] == 60

    @pytest.mark.parametrize("changes", [
        {"approval_status": "APPROVED", "ai_eligibility": "NOT_ELIGIBLE"},  # approved against the AI, no override
        {"approval_status": "REJECTED", "ai_eligibility": "ELIGIBLE"},      # business reason, not a verdict
        {"ai_input": "not json"},
        {"override_eligibility": "ELIGIBLE", "ai_eligibility": "NOT_ELIGIBLE", "review_note": "  "},
    ])
    def test_ambiguous_or_unusable_rows_are_skipped(self, changes):
        assert cf.lead_example(lead_row(**changes)) is None

    def test_every_target_passes_the_backend_validator(self):
        for row in (lead_row(), lead_row(override_eligibility="NOT_ELIGIBLE", review_note="No.")):
            fmt.parse_lead_evaluation(cf.lead_example(row)["messages"][2]["content"], "LD100")

    def test_meta_carries_the_rule_outcome_evaluate_py_needs(self):
        meta = json.loads(cf.lead_example(lead_row())["meta"])
        assert meta["combined"] == "UNKNOWN" and meta["mode"] == "ALL"


CONTENT_DOC = {
    "brief": {"campaign_name": "Sales Cloud Launch", "product_name": "TechCRM Sales Cloud",
              "call_to_action": "Book a demo"},
    "style": {"tone": "Informative", "language": "English", "additional_instructions": ""},
    "channels": {
        "email": {"subject": "max 120 characters, required", "body": "max 4000 characters, required",
                  "cta": "max 40 characters, required"},
        "sms": {"text": "max 320 characters, required"},
    },
}
CONTENT_INPUT = fmt.to_java_json(CONTENT_DOC)
EMAIL = {"subject": "Meet TechCRM Sales Cloud", "body": "Hello {{first_name}}, book a demo of TechCRM Sales Cloud.",
         "cta": "Book a demo"}


class TestContentExamples:
    def test_edited_email_becomes_the_target_and_prompt_is_narrowed(self):
        rows = [{"id": 1, "channel": "EMAIL", "fields_json": json.dumps(EMAIL), "provenance": "HUMAN_EDITED",
                 "ai_input": CONTENT_INPUT}]
        [example] = cf.content_examples(rows)
        assert json.loads(example["messages"][1]["content"])["channels"].keys() == {"email"}
        assert target_of(example) == {"email": EMAIL}
        assert json.loads(example["meta"])["corrected"] is True

    def test_channels_failing_the_validator_are_dropped(self):
        too_long = dict(EMAIL, subject="x" * 200)
        rows = [{"id": 1, "channel": "EMAIL", "fields_json": json.dumps(too_long), "provenance": "AI_GENERATED",
                 "ai_input": CONTENT_INPUT}]
        assert cf.content_examples(rows) == []


class TestSplit:
    def test_benchmark_membership_is_stable_and_about_a_fifth(self):
        def example(i):
            return {"meta": json.dumps({"ref": f"lead:{i}"})}
        picks = [cf.goes_to_benchmark(example(i)) for i in range(2000)]
        assert picks == [cf.goes_to_benchmark(example(i)) for i in range(2000)]
        assert 0.15 < sum(picks) / len(picks) < 0.25

    def test_build_writes_seed_plus_feedback_benchmark(self, tmp_path, monkeypatch):
        heldout = tmp_path / "heldout.jsonl"
        heldout.write_text(json.dumps({"task": "lead_evaluation", "messages": [], "meta": "{}"}) + "\n",
                           encoding="utf-8")
        for name, path in (("FEEDBACK_PATH", "feedback.jsonl"), ("BENCHMARK_PATH", "benchmark.jsonl"),
                           ("BENCHMARK_SEED_PATH", "seed.jsonl")):
            monkeypatch.setattr(cf, name, tmp_path / path)
        monkeypatch.setattr(cf, "HELDOUT_PATH", heldout)
        rows = [lead_row(id=i) for i in range(50)]
        counts = cf.build(rows, [])
        train = cf.read_jsonl(tmp_path / "feedback.jsonl")
        bench = cf.read_jsonl(tmp_path / "benchmark.jsonl")
        assert len(train) + len(bench) - 1 == 50          # one seed row
        assert counts["feedback_train"]["lead_evaluation"] == len(train)
        # Retraining later must not change the yardstick.
        heldout.write_text("", encoding="utf-8")
        cf.build(rows, [])
        assert len(cf.read_jsonl(tmp_path / "benchmark.jsonl")) == len(bench)


def results(valid=1.0, accuracy=0.9, guard=0.0, mae=6.0, content=0.95, real_n=0, real_acc=None):
    return {
        "lead_evaluation": {"n": 80, "valid": valid, "eligibility_accuracy": accuracy,
                            "rule_guard_would_fire": guard, "score_mae": mae},
        "lead_evaluation_feedback": {"n": real_n, "eligibility_accuracy": real_acc},
        "content_generation": {"n": 30, "valid_first_attempt": content, "channel_valid": content},
    }


class TestDecide:
    def test_better_accuracy_is_promoted(self):
        assert retrain.decide(results(accuracy=0.90), results(accuracy=0.93))[0]

    def test_equal_accuracy_is_kept(self):
        assert not retrain.decide(results(), results())[0]

    def test_equal_accuracy_with_clearly_lower_score_error_is_promoted(self):
        assert retrain.decide(results(mae=6.0), results(mae=4.0))[0]

    @pytest.mark.parametrize("worse", [{"valid": 0.95}, {"guard": 0.05}, {"content": 0.80}])
    def test_guard_regressions_block_promotion_even_with_better_accuracy(self, worse):
        promote, reasons = retrain.decide(results(), results(accuracy=0.99, **worse))
        assert not promote and reasons

    def test_real_review_accuracy_decides_once_there_are_enough_rows(self):
        current = results(accuracy=0.95, real_n=12, real_acc=0.50)
        better_on_real = results(accuracy=0.94, real_n=12, real_acc=0.75)
        assert retrain.decide(current, better_on_real)[0]

    def test_few_real_rows_fall_back_to_overall_accuracy(self):
        current = results(accuracy=0.95, real_n=3, real_acc=0.33)
        assert not retrain.decide(current, results(accuracy=0.94, real_n=3, real_acc=1.0))[0]


class TestPromote:
    def test_candidate_replaces_weights_and_two_backups_are_kept(self, tmp_path, monkeypatch):
        monkeypatch.setattr(retrain, "MARKETING", tmp_path)
        monkeypatch.setattr(retrain, "WEIGHTS", tmp_path / "weights")
        monkeypatch.setattr(retrain, "CANDIDATE", tmp_path / "candidate")
        (tmp_path / "weights").mkdir()
        (tmp_path / "weights" / "v").write_text("0")
        for version, stamp in enumerate(["20261001-0200", "20261008-0200", "20261015-0200"], start=1):
            (tmp_path / "candidate").mkdir()
            (tmp_path / "candidate" / "v").write_text(str(version))
            retrain.promote(stamp)
        assert (tmp_path / "weights" / "v").read_text() == "3"
        assert not (tmp_path / "candidate").exists()
        assert sorted(p.name for p in tmp_path.glob("weights.prev-*")) == [
            "weights.prev-20261008-0200", "weights.prev-20261015-0200"]
