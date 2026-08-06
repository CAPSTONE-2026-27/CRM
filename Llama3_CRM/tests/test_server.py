"""
Wire-format tests for scripts/main.py.

The CRM's AiChatClient parses two specific shapes: `choices[0].message.content`
for a blocking call and `data: {...}` lines carrying `choices[0].delta.content`
for a stream. If either drifts, all four AI modules go quiet — they catch the
failure and return null, so nothing surfaces as an error. These tests pin the
shapes down.

Generation is stubbed, so none of this needs a GPU or the 16GB of weights.

Run:
    python -m pytest tests/ -v
"""

import json
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))

import main  # noqa: E402

LEAD_SYSTEM_PROMPT = (
    "You are a CRM lead-scoring assistant. Score the lead from 0-100 based on fit and intent "
    "signals. Respond with ONLY strict JSON, no prose, no markdown fences."
)

LEAD_USER_MESSAGE = (
    "Contact: Priya Menon\n"
    "Company: Pioneer Hospitality K.K.\n"
    "Company size: 474 employees\n"
    "Estimated deal value: 15409421.00\n"
    "Notes from sales executive: Ready to buy, only finalizing the vendor choice."
)

FINE_TUNE_OUTPUT = """Lead Score: 45/100

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


class _FakeTokenizer:
    """Just enough surface for build_prompt_from_messages()."""

    eos_token_id = 128009

    def apply_chat_template(self, messages, tokenize=False, add_generation_prompt=True):
        return "\n".join(f"<{m['role']}>{m['content']}" for m in messages)


@pytest.fixture
def client(monkeypatch):
    """A server whose weights are 'loaded' but whose generation is stubbed."""
    calls = {}

    def fake_generate(prompt, *, use_adapter, max_new_tokens, temperature):
        calls.update(
            prompt=prompt, use_adapter=use_adapter, max_new_tokens=max_new_tokens,
            temperature=temperature,
        )
        return FINE_TUNE_OUTPUT if use_adapter else "The base model's answer."

    def fake_stream(prompt, *, use_adapter, max_new_tokens, temperature):
        calls.update(prompt=prompt, use_adapter=use_adapter, streamed=True)
        yield from ["Focus ", "on ", "the ", "renewal."]

    monkeypatch.setattr(main, "model", object())
    monkeypatch.setattr(main, "tokenizer", _FakeTokenizer())
    monkeypatch.setattr(main, "generate_text", fake_generate)
    monkeypatch.setattr(main, "stream_text", fake_stream)

    test_client = TestClient(main.app)
    test_client.calls = calls
    return test_client


def _chat(client, system, user, **extra):
    return client.post(
        "/v1/chat/completions",
        json={
            "model": "crm-llama-3.1-8b-lora",
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            **extra,
        },
    )


class TestBlockingResponseShape:
    def test_matches_what_aichatclient_deserialises(self, client):
        body = _chat(client, "You are Deal coach.", "How do I revive a stalled deal?").json()
        # AiChatClient.ChatResponse -> Choice -> ResponseMessage.content
        assert body["choices"][0]["message"]["content"] == "The base model's answer."
        assert body["choices"][0]["message"]["role"] == "assistant"
        assert body["object"] == "chat.completion"

    def test_lead_scoring_content_is_the_json_aiscoringclient_expects(self, client):
        body = _chat(client, LEAD_SYSTEM_PROMPT, LEAD_USER_MESSAGE).json()
        payload = json.loads(body["choices"][0]["message"]["content"])
        assert payload["score"] == 75
        assert payload["label"] == "Hot"
        assert payload["qualificationStatus"] == "QUALIFIED"

    def test_legacy_path_without_the_v1_prefix_also_answers(self, client):
        # Tolerates ai.base-url being set with or without /v1.
        response = client.post(
            "/chat/completions",
            json={"messages": [{"role": "user", "content": "hi"}]},
        )
        assert response.status_code == 200


class TestRouting:
    def test_lead_scoring_enables_the_adapter(self, client):
        _chat(client, LEAD_SYSTEM_PROMPT, LEAD_USER_MESSAGE)
        assert client.calls["use_adapter"] is True
        # Built from the trained prompt, not passed through from the CRM.
        assert "Employees Count: 474" in client.calls["prompt"]
        assert "You are an AI CRM Lead Management Assistant" in client.calls["prompt"]

    @pytest.mark.parametrize(
        "system",
        [
            "You are a CRM assistant supporting a sales representative after a customer meeting.",
            "You are a B2B sales analyst.",
            "You are Deal coach, an assistant inside a CRM used by a sales team.",
        ],
    )
    def test_other_modules_bypass_the_adapter(self, client, system):
        _chat(client, system, "notes")
        assert client.calls["use_adapter"] is False

    def test_json_tasks_decode_greedily(self, client):
        _chat(client, "Respond with ONLY strict JSON, no prose.", "extract this")
        assert client.calls["temperature"] == 0.0

    def test_coach_chat_samples(self, client):
        _chat(client, "You are Deal coach. Be concise.", "advice?")
        assert client.calls["temperature"] == main.CHAT_TEMPERATURE

    def test_explicit_temperature_wins(self, client):
        _chat(client, "You are Deal coach.", "advice?", temperature=0.2)
        assert client.calls["temperature"] == 0.2

    def test_deal_analysis_gets_room_for_fourteen_parameters(self, client):
        _chat(client, "You are a B2B sales analyst.", "write-up")
        assert client.calls["max_new_tokens"] >= 1024


class TestStreamingWireFormat:
    def _events(self, client, system="You are Deal coach.", user="advice?"):
        response = _chat(client, system, user, stream=True)
        assert response.status_code == 200
        assert response.headers["content-type"].startswith("text/event-stream")
        return [line for line in response.text.splitlines() if line.startswith("data:")]

    def test_deltas_are_where_aichatclient_looks_for_them(self, client):
        # AiChatClient.extractDelta reads choices[0].delta.content.
        events = self._events(client)
        contents = []
        for line in events:
            payload = line[len("data:") :].strip()
            if payload == "[DONE]":
                continue
            delta = json.loads(payload)["choices"][0]["delta"]
            if "content" in delta:
                contents.append(delta["content"])
        assert "".join(contents) == "Focus on the renewal."

    def test_stream_terminates_with_the_done_sentinel(self, client):
        # AiChatClient skips [DONE] explicitly; without it the reader waits for
        # the socket to close instead.
        assert self._events(client)[-1].strip() == "data: [DONE]"

    def test_every_chunk_is_one_parseable_json_document(self, client):
        for line in self._events(client):
            payload = line[len("data:") :].strip()
            if payload != "[DONE]":
                assert json.loads(payload)["object"] == "chat.completion.chunk"

    def test_lead_scoring_streams_the_bridged_json_not_the_raw_text(self, client):
        events = self._events(client, LEAD_SYSTEM_PROMPT, LEAD_USER_MESSAGE)
        content = "".join(
            json.loads(line[len("data:") :].strip())["choices"][0]["delta"].get("content", "")
            for line in events
            if line[len("data:") :].strip() != "[DONE]"
        )
        assert json.loads(content)["score"] == 75


class TestDegradation:
    def test_unloaded_model_returns_503_so_java_falls_back(self, monkeypatch):
        # AiChatClient catches the RestClientException, logs, and returns null —
        # the same signal an unreachable Groq gave. Every CRM fallback then runs.
        monkeypatch.setattr(main, "model", None)
        monkeypatch.setattr(main, "tokenizer", None)
        response = TestClient(main.app).post(
            "/v1/chat/completions", json={"messages": [{"role": "user", "content": "hi"}]}
        )
        assert response.status_code == 503

    def test_inference_failure_returns_500(self, client, monkeypatch):
        def boom(*args, **kwargs):
            raise RuntimeError("CUDA out of memory")

        monkeypatch.setattr(main, "generate_text", boom)
        assert _chat(client, "You are Deal coach.", "hi").status_code == 500

    def test_health_reports_readiness(self, client):
        assert client.get("/health").json()["status"] == "ok"


class TestPlainPromptEndpoints:
    def test_generate_returns_the_documented_shape(self, client):
        body = client.post("/generate", json={"prompt": "Summarise this deal."}).json()
        assert body == {"response": "The base model's answer."}

    def test_stream_yields_raw_tokens(self, client):
        response = client.post("/stream", json={"prompt": "Summarise this deal."})
        assert response.status_code == 200
        assert response.text == "Focus on the renewal."
