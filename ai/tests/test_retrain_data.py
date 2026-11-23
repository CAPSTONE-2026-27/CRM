"""
Guarantees the 2026-10 retrain depends on, one class per adapter.

Each adapter's held-out score used to measure recall of its generator: the
eval rows shared wording with the training rows. These tests pin the fixes --
disjoint phrasings, journey-level splits, hand-written test sets that reuse no
generator text -- so a regenerated dataset cannot quietly bring the overlap
back. They also pin the label and prompt corrections made for this retrain.

Generators are read with ast, not imported: every adapter has a module named
prompt_format, and a generator's bare `import prompt_format` resolves to
whichever adapter another test loaded first.

Run:
    python -m pytest tests/test_retrain_data.py -v
"""

import ast
import json
import re
import sys
from pathlib import Path

import pytest

AI_ROOT = Path(__file__).resolve().parent.parent
ADAPTERS = AI_ROOT / "adapters"
sys.path.insert(0, str(AI_ROOT))

from adapters.deal_state import prompt_format as deal_fmt  # noqa: E402
from adapters.lead_meeting import prompt_format as meeting_fmt  # noqa: E402
from adapters.lead_scoring import prompt_format as scoring_fmt  # noqa: E402
from adapters.marketing import prompt_format as marketing_fmt  # noqa: E402


def constant(path: Path, name: str):
    """A module-level literal, read without importing the module."""
    for node in ast.parse(path.read_text(encoding="utf-8")).body:
        if isinstance(node, ast.Assign) and any(getattr(t, "id", None) == name for t in node.targets):
            return ast.literal_eval(node.value)
    raise AssertionError(f"{name} not found in {path}")


def jsonl(path: Path) -> list:
    return [json.loads(line) for line in path.open(encoding="utf-8") if line.strip()]


def sentences(text: str) -> set:
    text = re.sub(r"^(Mtg notes:|- |• )", "", text, flags=re.MULTILINE)
    return {s.strip(" -").lower() for s in re.split(r"(?<=[.!?])\s+|\n", text) if len(s.strip()) > 15}


# ============================================================
# LEAD SCORING
# ============================================================
class TestLeadScoring:
    GENERATOR = ADAPTERS / "lead_scoring" / "data" / "generate_dataset.py"
    REALISTIC = ADAPTERS / "lead_scoring" / "eval" / "realistic.jsonl"

    def phrases(self, name):
        return {tier: [p.lower().rstrip(".") for p in options]
                for tier, options in constant(self.GENERATOR, name).items()}

    def test_eval_phrases_never_appear_in_train_phrases(self):
        train = {p for options in self.phrases("TRAIN_PHRASES").values() for p in options}
        held = {p for options in self.phrases("EVAL_PHRASES").values() for p in options}
        assert not train & held

    def test_eval_split_requirements_use_no_train_phrase(self):
        train = [p for options in self.phrases("TRAIN_PHRASES").values() for p in options]
        rows = jsonl(ADAPTERS / "lead_scoring" / "data" / "train.jsonl")
        leaked = [row["input"] for row in rows if row["split"] == "eval"
                  and any(p in scoring_fmt._parse_input_fields(row["input"])["Customer Requirement"].lower()
                          for p in train)]
        assert leaked == []

    def test_realistic_notes_reuse_no_generator_phrase(self):
        bank = [p for name in ("TRAIN_PHRASES", "EVAL_PHRASES")
                for options in self.phrases(name).values() for p in options]
        for row in jsonl(self.REALISTIC):
            note = scoring_fmt._parse_input_fields(row["input"])["Customer Requirement"].lower()
            assert not any(p in note or note in p for p in bank), row["id"]

    def test_realistic_rows_are_complete(self):
        rows = jsonl(self.REALISTIC)
        assert len({r["id"] for r in rows}) == len(rows) == 40
        for row in rows:
            fields = scoring_fmt._parse_input_fields(row["input"])
            assert row["requirement_points"] in {0, 5, 10, 15, 20}
            assert "Company Location" not in fields  # the CRM bridge never sends one
            assert fields["Purchase Timeline"] in scoring_fmt.TIMELINE_POINTS

    def test_chat_template_renders_the_serving_prompt(self):
        transformers = pytest.importorskip("transformers")
        model_dir = AI_ROOT / "base-model" / "Llama-3.1-8B-Instruct"
        if not (model_dir / "tokenizer.json").exists():
            pytest.skip("base model tokenizer not present")
        tokenizer = transformers.AutoTokenizer.from_pretrained(str(model_dir), local_files_only=True)
        tokenizer.chat_template = scoring_fmt.CHAT_TEMPLATE
        lead = "Company Name: Acme\nEmployees Count: 120"
        reply = "Lead Score: 40/100"
        assert tokenizer.apply_chat_template(scoring_fmt.build_messages(lead, reply), tokenize=False) == \
            scoring_fmt.build_llama3_prompt(scoring_fmt.SYSTEM_PROMPT, scoring_fmt.build_user_turn(lead), reply)
        assert tokenizer.apply_chat_template(scoring_fmt.build_messages(lead), tokenize=False,
                                             add_generation_prompt=True) == \
            scoring_fmt.build_llama3_prompt(scoring_fmt.SYSTEM_PROMPT, scoring_fmt.build_user_turn(lead))


# ============================================================
# LEAD MEETING
# ============================================================
class TestLeadMeeting:
    DATA = ADAPTERS / "lead_meeting" / "data" / "train.jsonl"
    FIXES = ADAPTERS / "lead_meeting" / "data" / "label_fixes.json"
    JAVA = AI_ROOT.parent / "backend" / "src" / "main" / "java" / "com" / "techcrm" / "crm" / "lead" / \
        "score" / "LeadMeetingExtractionClient.java"

    def test_every_label_fix_is_applied(self):
        rows = {r["meeting_id"]: r for r in jsonl(self.DATA)}
        fixes = json.loads(self.FIXES.read_text(encoding="utf-8"))["fixes"]
        stale = [mid for mid, (sentiment, _) in fixes.items()
                 if rows[mid]["signals"]["customer_sentiment"] != sentiment
                 or json.loads(rows[mid]["messages"][2]["content"])["customer_sentiment"] != sentiment]
        assert stale == [], "run adapters/lead_meeting/data/fix_labels.py"

    def test_training_rows_carry_the_current_prompt(self):
        # train.py trains on the stored messages, so a SYSTEM_PROMPT edit that
        # was not re-rendered into the data never reaches the model.
        stale = [r["meeting_id"] for r in jsonl(self.DATA)
                 if r["messages"] != meeting_fmt.build_messages(r["meeting_notes"], r["signals"])]
        assert stale == [], "run adapters/lead_meeting/data/fix_labels.py"

    def test_java_prompt_mirrors_python_prompt(self):
        if not self.JAVA.exists():
            pytest.skip("backend source not present")
        source = self.JAVA.read_text(encoding="utf-8").replace("\r\n", "\n")
        block = source.split('SYSTEM_PROMPT = """\n', 1)[1].split('""";', 1)[0]
        lines = block.split("\n")
        indent = min(len(line) - len(line.lstrip()) for line in lines if line.strip())
        text = "\n".join(line[indent:] if line.strip() else "" for line in lines)
        assert text.replace("\\\n", "") == meeting_fmt.SYSTEM_PROMPT

    def test_realistic_rows_are_in_vocabulary(self):
        rows = jsonl(ADAPTERS / "lead_meeting" / "eval" / "realistic.jsonl")
        assert len({r["meeting_id"] for r in rows}) == len(rows) == 40
        for row in rows:
            for field in meeting_fmt.FIELD_ORDER:
                assert row["signals"][field] in meeting_fmt.ALLOWED_VALUES[field], row["meeting_id"]

    def test_realistic_notes_are_not_training_notes(self):
        train = set().union(*(sentences(r["meeting_notes"]) for r in jsonl(self.DATA)))
        for row in jsonl(ADAPTERS / "lead_meeting" / "eval" / "realistic.jsonl"):
            assert not sentences(row["meeting_notes"]) & train, row["meeting_id"]


# ============================================================
# DEAL STATE
# ============================================================
class TestDealState:
    GENERATOR = ADAPTERS / "deal_state" / "data" / "generate_dataset.py"
    DATA = ADAPTERS / "deal_state" / "data" / "train.jsonl"
    REALISTIC = ADAPTERS / "deal_state" / "eval" / "realistic.jsonl"

    @pytest.fixture(scope="class")
    @classmethod
    def rows(cls):
        return jsonl(cls.DATA)

    def test_no_journey_is_on_both_sides_of_the_split(self, rows):
        sides = {}
        for row in rows:
            sides.setdefault(row["meta"]["journey"], set()).add(row["split"])
        assert all(len(s) == 1 for s in sides.values())
        assert any(row["split"] == "eval" for row in rows)

    def test_eval_evidence_never_appears_in_train_notes(self, rows):
        filler = [f.replace("{contact}", "").strip(" .").lower() for f in constant(self.GENERATOR, "FILLER")]

        def evidence(text):
            return {s for s in sentences(text) if not any(f in s for f in filler)}

        train = set().union(*(evidence(r["meeting_notes"]) for r in rows if r["split"] == "train"))
        shared = [s for r in rows if r["split"] == "eval" for s in evidence(r["meeting_notes"]) if s in train]
        assert shared == []

    def test_every_value_has_held_out_phrasings(self):
        for field, values in constant(self.GENERATOR, "MOVE_NOTES").items():
            for value, options in values.items():
                assert len(options) >= 3, (field, value)

    def test_realistic_expected_states_are_what_the_server_returns(self):
        for row in jsonl(self.REALISTIC):
            previous, expected = row["previous_state"], row["expected_state"]
            coerced, repairs = deal_fmt.coerce_state(expected)
            assert repairs == [], row["id"]
            derived, corrected = deal_fmt.apply_derived_fields(previous, coerced)
            assert corrected == [], row["id"]
            moved = {f for f in deal_fmt.FIELD_ORDER if str(previous[f]) != str(expected[f])}
            assert moved - set(deal_fmt.DERIVED_FIELDS) <= set(row["changed_fields"]), row["id"]


# ============================================================
# MARKETING
# ============================================================
class TestMarketing:
    def test_no_training_copy_is_near_a_character_limit(self):
        over = [marketing_fmt.over_margin(json.loads(r["messages"][2]["content"]))
                for r in jsonl(ADAPTERS / "marketing" / "data" / "train.jsonl")
                if r["task"] == "content_generation"]
        assert not any(over), "run adapters/marketing/data/generate_dataset.py build"

    def test_over_margin_flags_only_fields_past_the_margin(self):
        assert marketing_fmt.over_margin({"email": {"preheader": "x" * 135}}) == []
        assert marketing_fmt.over_margin({"email": {"preheader": "x" * 136}}) == ["email.preheader 136/150"]

    def test_realistic_lead_targets_pass_the_backend_validator(self):
        rows = jsonl(ADAPTERS / "marketing" / "eval" / "realistic.jsonl")
        leads = [r for r in rows if r["task"] == "lead_evaluation"]
        assert leads and any(r["task"] == "content_generation" for r in rows)
        for row in leads:
            target = json.loads(row["messages"][2]["content"])
            marketing_fmt.parse_lead_evaluation(row["messages"][2]["content"], target["lead_id"])
            if json.loads(row["meta"])["combined"] == "FAIL":
                assert target["eligibility"] == "not_eligible", target["lead_id"]
