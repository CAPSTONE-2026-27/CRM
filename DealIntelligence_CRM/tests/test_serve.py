"""
API tests for the Deal Intelligence inference service.

Generation is stubbed, so none of this needs a GPU or the base weights. What is
being tested is the contract around the model, which is where the failures that
matter live: a malformed reply must not become a 500, an unrecognised value must
not reach the scorer, and a missing previous state must not crash the first
meeting on an opportunity.

Run:
    python -m pytest tests/test_serve.py -v
"""

import json
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))

import deal_state_format as fmt  # noqa: E402
import serve  # noqa: E402


def _state(**overrides):
    state = {f: fmt.DEFAULTS[f] for f in fmt.FIELD_ORDER}
    state.update(overrides)
    return state


@pytest.fixture
def client(monkeypatch):
    """A service whose weights are 'loaded' but whose generation is stubbed."""
    captured = {}

    def fake_generate(prompt):
        captured["prompt"] = prompt
        return captured.get("reply", json.dumps(captured.get("state", _state())))

    monkeypatch.setattr(serve, "model", object())
    monkeypatch.setattr(serve, "tokenizer", object())
    monkeypatch.setattr(serve, "adapter_loaded", True)
    monkeypatch.setattr(serve, "_generate", fake_generate)

    test_client = TestClient(serve.app)
    test_client.captured = captured
    return test_client


class TestHealth:
    def test_reports_service_identity_and_version(self, client):
        body = client.get("/health").json()
        assert body["service"] == "deal-intelligence"
        assert body["version"] == fmt.CONTRACT_VERSION
        assert body["status"] == "ok"

    def test_health_says_whether_the_adapter_is_loaded(self, client):
        # Base-model output on this task is not fit for scoring, so a consumer
        # must be able to tell the difference without reading the logs.
        assert client.get("/health").json()["adapter_loaded"] is True


class TestSchemaEndpoint:
    def test_publishes_the_emitted_contract(self, client):
        body = client.get("/v1/schema").json()
        assert body["field_order"] == fmt.FIELD_ORDER
        assert body["version"] == fmt.CONTRACT_VERSION
        assert body["no_objections_sentinel"] == fmt.NO_OBJECTIONS

    def test_published_values_match_what_coercion_accepts(self, client):
        body = client.get("/v1/schema").json()
        for field, allowed in body["categorical_values"].items():
            for value in allowed:
                assert fmt.snap(field, value) == value

    def test_buying_intent_does_not_advertise_very_high(self, client):
        # The scorer rejects it; publishing it would invite a consumer to send
        # something that fails at scoring time rather than here.
        assert "Very High" not in client.get("/v1/schema").json()["categorical_values"]["buying_intent"]


class TestDealStateEndpoint:
    def _post(self, client, previous=None, notes="The CFO approved the full budget."):
        payload = {"meeting_notes": notes}
        if previous is not None:
            payload["previous_state"] = previous
        return client.post("/v1/deal-state", json=payload)

    def test_returns_a_complete_state(self, client):
        body = self._post(client, _state()).json()
        assert list(body["state"]) == fmt.FIELD_ORDER
        assert len(body["state"]) == 17

    def test_state_is_always_scoreable(self, client):
        # Whatever the model said, what leaves this service must be coercible
        # with nothing left to repair.
        client.captured["reply"] = "total garbage, no json here"
        body = self._post(client, _state()).json()
        _, repairs = fmt.coerce_state(body["state"])
        assert repairs == []

    def test_first_meeting_needs_no_previous_state(self, client):
        client.captured["state"] = _state(total_meetings=1)
        response = self._post(client, previous=None)
        assert response.status_code == 200
        assert response.json()["state"]["total_meetings"] == 1

    def test_previous_state_reaches_the_prompt(self, client):
        self._post(client, _state(budget_status="Partially Approved"))
        assert "Partially Approved" in client.captured["prompt"]

    def test_meeting_notes_reach_the_prompt(self, client):
        self._post(client, _state(), notes="Their CTO joined and signed off.")
        assert "Their CTO joined and signed off." in client.captured["prompt"]

    def test_changed_fields_lists_only_what_moved(self, client):
        previous = _state(budget_status="Under Review", customer_sentiment="Neutral")
        client.captured["state"] = _state(
            budget_status="Fully Approved", customer_sentiment="Neutral"
        )
        body = self._post(client, previous).json()
        assert "budget_status" in body["changed_fields"]
        assert "customer_sentiment" not in body["changed_fields"]

    def test_out_of_vocabulary_values_are_snapped_and_reported(self, client):
        client.captured["state"] = _state(customer_sentiment="Ecstatic")
        body = self._post(client, _state()).json()
        assert body["state"]["customer_sentiment"] in fmt.SENTIMENT_VALUES
        assert any("customer_sentiment" in r for r in body["repairs"])

    def test_objection_list_is_flattened_to_the_string_xgboost_expects(self, client):
        # The model will emit a list despite the prompt; the pipeline splits on
        # [;,] and a stringified list matches nothing.
        raw = _state()
        raw["main_objections"] = ["Price Too High", "Missing Features"]
        client.captured["state"] = raw
        state = self._post(client, _state()).json()["state"]
        assert state["main_objections"] == "Price Too High; Missing Features"

    def test_relationship_strength_stays_numeric(self, client):
        # serve_api declares float(ge=0, le=10); a string is a 422 downstream.
        raw = _state()
        raw["relationship_strength"] = "Very Strong"
        client.captured["state"] = raw
        value = self._post(client, _state()).json()["state"]["relationship_strength"]
        assert isinstance(value, (int, float))
        assert 0 <= value <= 10

    def test_repairs_are_surfaced_not_swallowed(self, client):
        # A state needing six repairs and one needing none produce equally
        # confident deal scores. This list is the only thing telling them apart.
        client.captured["state"] = {"customer_sentiment": "Positive"}  # 16 fields missing
        body = self._post(client, _state()).json()
        assert len(body["repairs"]) >= 10


class TestDegradation:
    def _post(self, client, **kw):
        return client.post("/v1/deal-state", json={"meeting_notes": "x", **kw})

    def test_unparseable_reply_carries_the_previous_state_forward(self, client):
        # Losing an opportunity's whole state because one generation was
        # malformed is worse than a state that did not move.
        previous = _state(budget_status="Fully Approved", total_meetings=4)
        client.captured["reply"] = "I'm sorry, I can't help with that."
        body = self._post(client, previous_state=previous).json()
        assert body["state"]["budget_status"] == "Fully Approved"
        assert body["state"]["total_meetings"] == 5  # the meeting still happened
        assert any("not-json" in r for r in body["repairs"])

    def test_unloaded_model_returns_503_not_a_fabricated_state(self, monkeypatch):
        monkeypatch.setattr(serve, "model", None)
        monkeypatch.setattr(serve, "tokenizer", None)
        response = TestClient(serve.app).post(
            "/v1/deal-state", json={"meeting_notes": "x"}
        )
        assert response.status_code == 503

    def test_generation_failure_returns_500(self, client, monkeypatch):
        def boom(_):
            raise RuntimeError("CUDA out of memory")

        monkeypatch.setattr(serve, "_generate", boom)
        assert self._post(client).status_code == 500

    def test_empty_meeting_notes_are_rejected(self, client):
        assert client.post("/v1/deal-state", json={"meeting_notes": ""}).status_code == 422


class TestIsolationFromLeadScoring:
    """The new service must not touch the existing lead-scoring system."""

    def test_serves_on_its_own_port(self):
        assert serve.PORT == 8002  # 8000 = XGBoost, 8001 = lead scoring

    def test_uses_its_own_adapter_not_the_lead_scoring_one(self):
        assert "deal_state" in str(serve.ADAPTER_PATH)
        assert "lead_management" not in str(serve.ADAPTER_PATH)

    def test_imports_nothing_from_the_lead_scoring_project(self):
        # The base model directory is shared read-only; code is not.
        for script in (ROOT / "scripts").glob("*.py"):
            source = script.read_text(encoding="utf-8")
            for banned in ("import prompt_format", "from prompt_format",
                           "import main", "from main import"):
                assert banned not in source, f"{script.name} imports from Llama3_CRM"

    def test_base_model_is_only_ever_read(self):
        # Shared weights on disk: a write from here would corrupt the running
        # lead-scoring service.
        for script in (ROOT / "scripts").glob("*.py"):
            source = script.read_text(encoding="utf-8")
            if "BASE_MODEL_PATH" in source or "MODEL_PATH" in source:
                assert "save_pretrained(str(MODEL_PATH))" not in source
                assert "save_pretrained(str(BASE_MODEL_PATH))" not in source
