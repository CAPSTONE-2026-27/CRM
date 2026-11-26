"""
The four derived deal-state numerics are computed, not generated.

total_meetings, lead_score, relationship_strength and engagement_score are fixed
functions of the categorical fields. The adapter reads the categoricals from the
notes almost perfectly but lands the arithmetic badly (lead_score ~39% on the
held-out split), so /v1/deal-state recomputes them after generation.

That is only safe while the computing rule is the rule the training targets were
built with. TestTrainingDataAgreement pins exactly that; the rest pins that the
server actually applies it.

Run:
    python -m pytest tests/ -v
"""

import json
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

AI_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(AI_ROOT))
sys.path.insert(0, str(AI_ROOT / "server"))

from adapters.deal_state import prompt_format as fmt  # noqa: E402

import main  # noqa: E402

TRAIN_JSONL = AI_ROOT / "adapters" / "deal_state" / "data" / "train.jsonl"


def _rows():
    with TRAIN_JSONL.open(encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            previous = fmt.extract_state(row["messages"][1]["content"])
            target = json.loads(row["messages"][2]["content"])
            yield previous, target


class TestTrainingDataAgreement:
    def test_every_training_target_reproduces(self):
        # If the generator's rules and these functions ever diverge, the server
        # would overwrite correct readings with values the adapter was never
        # trained to agree with.
        mismatches = []
        for index, (previous, target) in enumerate(_rows()):
            derived, corrected = fmt.apply_derived_fields(previous, target)
            if corrected:
                mismatches.append((index, corrected))
        assert mismatches == []


class TestApplyDerivedFields:
    def _pair(self):
        previous, target = next(_rows())
        return previous, target

    def test_wrong_arithmetic_is_replaced_and_reported(self):
        previous, target = self._pair()
        generated = dict(target, lead_score=(target["lead_score"] + 17) % 100,
                     engagement_score=0, relationship_strength=0.0, total_meetings=99)

        state, corrected = fmt.apply_derived_fields(previous, generated)

        for field in fmt.DERIVED_FIELDS:
            assert float(state[field]) == float(target[field])
        assert sorted(entry.split("=")[0] for entry in corrected) == sorted(fmt.DERIVED_FIELDS)

    def test_categoricals_are_left_exactly_as_read(self):
        previous, target = self._pair()
        state, _ = fmt.apply_derived_fields(previous, dict(target, lead_score=0))
        for field in fmt.FIELD_ORDER:
            if field not in fmt.DERIVED_FIELDS:
                assert state[field] == target[field]

    def test_does_not_mutate_its_input(self):
        previous, target = self._pair()
        generated = dict(target, lead_score=0)
        fmt.apply_derived_fields(previous, generated)
        assert generated["lead_score"] == 0

    def test_relationship_moves_at_most_one_point(self):
        previous, target = self._pair()
        previous = dict(previous, relationship_strength=5.0, customer_sentiment="Negative",
                        decision_maker_involvement="No")
        generated = dict(target, customer_sentiment="Positive", meeting_outcome="Verbal Agreement",
                         decision_maker_involvement="Yes")
        state, _ = fmt.apply_derived_fields(previous, generated)
        assert state["relationship_strength"] == 6.0


class TestDealStateRoute:
    @pytest.fixture
    def client(self, monkeypatch):
        previous, target = next(_rows())
        wrong = dict(target, lead_score=3, engagement_score=97, relationship_strength=10.0)

        monkeypatch.setattr(main, "model", object())
        monkeypatch.setattr(main, "tokenizer", object())
        monkeypatch.setattr(main, "DEAL_STATE_ADAPTER_READY", True)
        monkeypatch.setattr(main, "generate_text", lambda prompt, **kwargs: json.dumps(wrong))

        test_client = TestClient(main.app)
        test_client.previous, test_client.target = previous, target
        return test_client

    def test_served_state_carries_computed_numerics(self, client):
        body = client.post("/v1/deal-state", json={
            "previous_state": client.previous, "meeting_notes": "notes"}).json()

        for field in fmt.DERIVED_FIELDS:
            assert float(body["state"][field]) == float(client.target[field]), field
        # Exact recomputation is not a repair: repairs still means "guessed".
        assert body["repairs"] == []
