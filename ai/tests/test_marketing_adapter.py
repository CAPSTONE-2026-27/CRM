"""
Tests for the marketing-automation adapter: its contract ports, rule engine,
dataset teacher and server routing. No GPU, no weights.

Run:
    python -m pytest tests/test_marketing_adapter.py -v
"""

import importlib.util
import json
import random
import sys
from pathlib import Path

import pytest

AI_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(AI_ROOT))
sys.path.insert(0, str(AI_ROOT / "server"))

from adapters.marketing import prompt_format as fmt  # noqa: E402
from adapters.marketing import rules  # noqa: E402

# Loaded by path under its own name: deal_state has a generate_dataset.py too,
# and a plain import would get whichever one another test module loaded first.
_spec = importlib.util.spec_from_file_location(
    "marketing_generate_dataset", AI_ROOT / "adapters" / "marketing" / "data" / "generate_dataset.py")
gen = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gen)


# ------------------------------------------------------------------ prompts
class TestPrompts:
    def test_prompts_are_the_java_files(self):
        java = AI_ROOT.parent / "backend" / "src" / "main" / "resources" / "marketing" / "prompts"
        assert fmt.LEAD_SYSTEM_PROMPT == (java / "lead-evaluation.system.txt").read_text(encoding="utf-8")
        assert fmt.CONTENT_SYSTEM_PROMPT == (java / "content-generation.system.txt").read_text(encoding="utf-8")

    def test_each_prompt_carries_its_own_marker_only(self):
        assert fmt.is_lead_evaluation(fmt.LEAD_SYSTEM_PROMPT)
        assert not fmt.is_lead_evaluation(fmt.CONTENT_SYSTEM_PROMPT)
        assert fmt.is_marketing(fmt.CONTENT_SYSTEM_PROMPT)

    @pytest.mark.parametrize("marker", ["you are a crm lead-scoring assistant",
                                        "you are a crm lead qualification analyst"])
    def test_prompts_never_trigger_another_adapter(self, marker):
        assert marker not in fmt.LEAD_SYSTEM_PROMPT.lower()
        assert marker not in fmt.CONTENT_SYSTEM_PROMPT.lower()

    def test_markers_match_prompt_library_java(self):
        java = (AI_ROOT.parent / "backend" / "src" / "main" / "java" / "com" / "techcrm" / "crm"
                / "marketing" / "ai" / "PromptLibrary.java").read_text(encoding="utf-8")
        assert f'"{fmt.LEAD_MARKER}"' in java
        assert f'"{fmt.CONTENT_MARKER}"' in java

    def test_prompt_string_matches_the_training_template(self):
        jinja2 = pytest.importorskip("jinja2")
        env = jinja2.Environment(extensions=["jinja2.ext.loopcontrols"])

        # {% generation %} is a transformers extension; plain jinja only needs it to be a no-op block.
        template = fmt.CHAT_TEMPLATE.replace("{%- generation %}", "").replace("{%- endgeneration %}", "")
        messages = [{"role": "system", "content": "SYS "}, {"role": "user", "content": " USER"}]
        rendered = env.from_string(template).render(messages=messages, bos_token="<|begin_of_text|>",
                                                    add_generation_prompt=True)
        assert rendered == fmt.build_llama3_prompt("SYS ", " USER")
        assert "Cutting Knowledge Date" not in rendered


# ------------------------------------------------------------------ builders
class TestBuilders:
    def test_lead_prompt_is_compact_jackson_json(self):
        prompt = fmt.build_lead_user_prompt(
            {"campaign_type": "upsell"}, "ALL",
            [{"label": "Industry is one of Retail", "outcome": "UNKNOWN", "actual": None}],
            {"lead_id": "LD1", "estimated_deal_value_inr": "500000.00"})
        assert prompt == ('{"campaign":{"campaign_type":"upsell"},"eligibility_rules":{"match":"ALL","rules":'
                          '[{"rule":"Industry is one of Retail","system_check":"UNKNOWN","lead_value":"not recorded"}]},'
                          '"lead":{"lead_id":"LD1","estimated_deal_value_inr":"500000.00"}}')

    def test_content_prompt_marks_required_fields(self):
        doc = json.loads(fmt.build_content_user_prompt({"product_name": "X"}, {"tone": "Friendly"}, ["sms"]))
        assert doc["channels"] == {"sms": {"text": "max 320 characters, required"}}


# ------------------------------------------------------------------ validators
class TestLeadValidator:
    GOOD = {"lead_id": "LD7", "score": 72, "eligibility": "eligible", "confidence": 0.8,
            "positive_factors": ["x"], "negative_factors": [], "reason": "Fits."}

    def test_accepts_a_good_reply_inside_prose(self):
        got = fmt.parse_lead_evaluation("Sure:\n```json\n" + json.dumps(self.GOOD) + "\n```", "LD7")
        assert got["eligible"] and got["score"] == 72

    @pytest.mark.parametrize("change,message", [
        ({"lead_id": "LD8"}, "lead_id did not match"),
        ({"score": 140}, "between 0 and 100"),
        ({"score": "high"}, "must be a number"),
        ({"eligibility": "maybe"}, "eligibility must be"),
        ({"reason": ""}, "reason is required"),
    ])
    def test_rejects_what_java_rejects(self, change, message):
        with pytest.raises(ValueError, match=message):
            fmt.parse_lead_evaluation(json.dumps({**self.GOOD, **change}), "LD7")


class TestContentValidator:
    GROUNDING = "TechCRM CPQ\n15% off annual plans\nStarts at ₹1,499 per user per month\nBook a demo"

    def _errors(self, channel, node):
        return fmt.validate_content(channel, node, self.GROUNDING)[1]

    def test_grounded_copy_passes(self):
        assert self._errors("sms", {"text": "Get 15% off TechCRM CPQ, from ₹1,499 per user. Book a demo."}) == []

    def test_invented_figure_is_rejected(self):
        errs = self._errors("sms", {"text": "Save 25% on TechCRM CPQ today."})
        assert any("'25%'" in e for e in errs)

    def test_single_digits_are_not_figures(self):
        assert self._errors("sms", {"text": "Set up in 3 steps."}) == []

    def test_unsupported_claim_is_rejected(self):
        assert any("free" in e for e in self._errors("sms", {"text": "Try it free."}))

    def test_length_and_required_fields(self):
        errs = self._errors("notification", {"title": "x" * 70})
        assert any("limit is 65" in e for e in errs)
        assert any("'body' is required" in e for e in errs)

    def test_placeholders(self):
        assert self._errors("sms", {"text": "Hi {{first_name}}, book a demo."}) == []
        assert any("unsupported placeholder" in e for e in self._errors("sms", {"text": "Hi {{name}}"}))
        assert any("unfilled placeholder" in e for e in self._errors("sms", {"text": "Hi [Your Name]"}))

    def test_missing_channel(self):
        accepted, rejected = fmt.check_content_reply('{"sms": {"text": "Book a demo."}}', ["sms", "email"],
                                                     self.GROUNDING)
        assert "sms" in accepted and rejected["email"] == ["EMAIL: missing from the reply"]


# ------------------------------------------------------------------ rules
class TestRules:
    def test_band_straddling_the_threshold_is_unknown(self):
        r = rules.evaluate_one("COMPANY_SIZE", "GTE", "100", {"company_size": "51-200"})
        assert r["outcome"] == "UNKNOWN"

    def test_band_entirely_above_passes_and_below_fails(self):
        assert rules.evaluate_one("COMPANY_SIZE", "GTE", "100", {"company_size": "201-500"})["outcome"] == "PASS"
        assert rules.evaluate_one("COMPANY_SIZE", "GTE", "100", {"company_size": "11-50"})["outcome"] == "FAIL"

    def test_open_band(self):
        assert rules.evaluate_one("COMPANY_SIZE", "GTE", "500", {"company_size": "1000+"})["outcome"] == "PASS"
        assert rules.evaluate_one("COMPANY_SIZE", "LTE", "500", {"company_size": "1000+"})["outcome"] == "FAIL"

    def test_missing_value_is_unknown_with_no_actual(self):
        r = rules.evaluate_one("INDUSTRY", "IN", "Retail, Telecom", {})
        assert r["outcome"] == "UNKNOWN" and r["actual"] is None

    def test_text_in_is_case_insensitive(self):
        assert rules.evaluate_one("INDUSTRY", "IN", "Retail, Telecom", {"industry": "telecom"})["outcome"] == "PASS"

    def test_timeline_band(self):
        assert rules.evaluate_one("PURCHASE_TIMELINE_DAYS", "LTE", "30",
                                  {"purchase_timeline": "Within 15 Days"})["outcome"] == "PASS"
        assert rules.evaluate_one("PURCHASE_TIMELINE_DAYS", "LTE", "30",
                                  {"purchase_timeline": "Within 2 Months"})["outcome"] == "UNKNOWN"

    def test_level_rule(self):
        assert rules.evaluate_one("INTEREST_LEVEL", "GTE", "Medium", {"interest_level": "High"})["outcome"] == "PASS"

    def test_label_matches_java_describe(self):
        assert rules.describe("ESTIMATED_DEAL_VALUE", "GTE", "500000") == "Estimated deal value (₹) >= 500000"

    @pytest.mark.parametrize("outcomes,mode,expected", [
        (["PASS", "FAIL"], "ALL", "FAIL"),
        (["PASS", "UNKNOWN"], "ALL", "UNKNOWN"),
        (["PASS", "FAIL"], "ANY", "PASS"),
        (["FAIL", "UNKNOWN"], "ANY", "UNKNOWN"),
        (["FAIL", "FAIL"], "ANY", "FAIL"),
        ([], "ALL", "PASS"),
    ])
    def test_combine(self, outcomes, mode, expected):
        assert rules.combine([{"outcome": o} for o in outcomes], mode) == expected


# ------------------------------------------------------------------ teacher
@pytest.fixture(scope="module")
def rows():
    return gen.lead_rows(300, seed=1)


class TestTeacher:
    def test_every_target_passes_the_backend_validator(self, rows):
        for row in rows:
            ref = json.loads(row["messages"][1]["content"].split("\n\n")[0])["lead"]["lead_id"]
            fmt.parse_lead_evaluation(row["messages"][2]["content"], ref)

    def test_never_eligible_when_a_required_rule_fails(self, rows):
        for row in rows:
            meta = json.loads(row["meta"])
            if meta["combined"] == "FAIL":
                assert meta["eligible"] == "not_eligible" and meta["score"] < 60

    def test_eligibility_and_score_agree(self, rows):
        for row in rows:
            meta = json.loads(row["meta"])
            assert (meta["eligible"] == "eligible") == (meta["score"] >= 60 and meta["combined"] != "FAIL")

    def test_factors_never_cite_missing_attributes(self, rows):
        for row in rows:
            lead = json.loads(row["messages"][1]["content"].split("\n\n")[0])["lead"]
            target = json.loads(row["messages"][2]["content"])
            text = " ".join(target["positive_factors"])
            if "industry" not in lead:
                assert not text.startswith("Industry")
            if "interest_level" not in lead:
                assert "product interest level" not in text

    def test_factor_limits(self, rows):
        for row in rows:
            target = json.loads(row["messages"][2]["content"])
            assert len(target["positive_factors"]) <= 5 and len(target["negative_factors"]) <= 5
            assert 0.0 <= target["confidence"] <= 1.0

    def test_generator_is_deterministic(self):
        assert gen.lead_rows(20, seed=3) == gen.lead_rows(20, seed=3)

    def test_content_briefs_ground_their_own_offer(self):
        rng = random.Random(0)
        for _ in range(50):
            brief, style, channels, _ = gen.make_content_request(rng)
            assert channels and all(ch in fmt.CHANNEL_FIELDS for ch in channels)
            grounding = fmt.grounding_text(brief, style)
            if "discount" in brief:
                assert brief["discount"] in grounding


# ------------------------------------------------------------------ server routing
class TestServerRouting:
    @pytest.fixture
    def client(self, monkeypatch):
        from fastapi.testclient import TestClient
        import main

        calls = {}

        def fake_generate(prompt, *, adapter, max_new_tokens, temperature, repetition_penalty=None):
            calls.update(prompt=prompt, adapter=adapter, max_new_tokens=max_new_tokens, temperature=temperature)
            return "{}"

        class _Tok:
            eos_token_id = 1

            def apply_chat_template(self, messages, tokenize=False, add_generation_prompt=True):
                return "STOCK:" + "|".join(m["content"] for m in messages)

        monkeypatch.setattr(main, "model", object())
        monkeypatch.setattr(main, "tokenizer", _Tok())
        monkeypatch.setattr(main, "generate_text", fake_generate)
        c = TestClient(main.app)
        c.calls, c.main = calls, main
        return c

    def _chat(self, client, system, temperature=None, max_tokens=None):
        body = {"messages": [{"role": "system", "content": system}, {"role": "user", "content": '{"lead":{}}'}]}
        if temperature is not None:
            body["temperature"] = temperature
        if max_tokens is not None:
            body["max_tokens"] = max_tokens
        return client.post("/v1/chat/completions", json=body)

    def test_routes_to_the_marketing_adapter_when_loaded(self, client, monkeypatch):
        monkeypatch.setattr(client.main, "MARKETING_ADAPTER_READY", True)
        self._chat(client, fmt.LEAD_SYSTEM_PROMPT, temperature=0.0, max_tokens=450)
        assert client.calls["adapter"] == client.main.MARKETING_ADAPTER
        assert client.calls["prompt"] == fmt.build_llama3_prompt(fmt.LEAD_SYSTEM_PROMPT, '{"lead":{}}')
        assert client.calls["max_new_tokens"] == 450

    def test_content_keeps_the_backend_temperature(self, client, monkeypatch):
        monkeypatch.setattr(client.main, "MARKETING_ADAPTER_READY", True)
        self._chat(client, fmt.CONTENT_SYSTEM_PROMPT, temperature=0.4)
        assert client.calls["adapter"] == client.main.MARKETING_ADAPTER
        assert client.calls["temperature"] == 0.4

    def test_falls_back_to_the_base_model_without_weights(self, client, monkeypatch):
        monkeypatch.setattr(client.main, "MARKETING_ADAPTER_READY", False)
        response = self._chat(client, fmt.CONTENT_SYSTEM_PROMPT, temperature=0.4)
        assert response.status_code == 200
        assert client.calls["adapter"] is None
        assert client.calls["prompt"].startswith("STOCK:")

    def test_other_json_tasks_stay_on_the_base_model(self, client, monkeypatch):
        monkeypatch.setattr(client.main, "MARKETING_ADAPTER_READY", True)
        self._chat(client, "You are a B2B sales analyst. Respond with only strict JSON.")
        assert client.calls["adapter"] is None

    def test_health_reports_the_marketing_adapter(self, client):
        assert "marketing" in client.get("/health").json()["adapters"]
