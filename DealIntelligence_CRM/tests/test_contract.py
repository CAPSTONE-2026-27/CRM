"""
Contract tests: this project's output must be consumable by the XGBoost scorer.

The model's whole job is to emit a feature vector XgBoost/serve_api.py accepts.
Nothing else stops the two projects drifting apart, and drift fails quietly:
an unrecognised one-hot value (customer_requirements, risk_factors) raises
nothing — get_dummies makes a column the bundle never saw, reindex drops it, and
every requirement/risk feature scores zero. The deal still gets a confident
score computed from evidence that vanished.

TestLiveBundle is the authority. It round-trips every emittable value through
the real transform_for_inference and disagrees with the pipeline *source* in at
least one place — see test_buying_intent_very_high_really_is_rejected.

Run:
    python -m pytest tests/ -v
"""

import ast
import csv
import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))

import deal_state_format as fmt  # noqa: E402

XGBOOST_DIR = ROOT.parent / "XgBoost"
PIPELINE = XGBOOST_DIR / "deal_score_pipeline.py"
DATASET = XGBOOST_DIR / "deal_score_dataset_200 .csv"
SERVE_API = XGBOOST_DIR / "serve_api.py"
TRAIN_JSONL = ROOT / "data" / "train.jsonl"

needs_xgboost_project = pytest.mark.skipif(
    not PIPELINE.exists(), reason="XgBoost project not present"
)


def _module_constant(name: str):
    """Read a module-level literal from deal_score_pipeline.py without importing
    it — importing pulls in xgboost, pandas and sklearn."""
    tree = ast.parse(PIPELINE.read_text(encoding="utf-8"))
    for node in tree.body:
        targets = node.targets if isinstance(node, ast.Assign) else (
            [node.target] if isinstance(node, ast.AnnAssign) else []
        )
        for target in targets:
            if isinstance(target, ast.Name) and target.id == name:
                value = node.value
                # frozenset({...}) / tuple([...]) are calls, not literals.
                if isinstance(value, ast.Call) and isinstance(value.func, ast.Name):
                    builder = {"frozenset": frozenset, "set": set,
                               "tuple": tuple, "list": list}.get(value.func.id)
                    if builder is not None and value.args:
                        return builder(ast.literal_eval(value.args[0]))
                return ast.literal_eval(value)
    raise AssertionError(f"{name} not found in {PIPELINE.name}")


def _dataset_values(column: str) -> set:
    with DATASET.open(encoding="utf-8-sig") as handle:
        return {row[column].strip() for row in csv.DictReader(handle) if row[column].strip()}


def _load_bundle():
    import joblib

    bundles = sorted((XGBOOST_DIR / "models").glob("*.pkl"))
    return joblib.load(bundles[-1]) if bundles else None


@needs_xgboost_project
class TestLiveBundle:
    """Round-trip every emittable value through the real scorer."""

    @pytest.fixture(scope="class")
    def bundle(self):
        pytest.importorskip("xgboost", reason="xgboost not installed")
        pytest.importorskip("pandas")
        bundle = _load_bundle()
        if bundle is None:
            pytest.skip("no model bundle in XgBoost/models")
        return bundle

    def _state(self, **overrides):
        state = {f: fmt.DEFAULTS[f] for f in fmt.FIELD_ORDER}
        state.update(overrides)
        return state

    def _transform(self, bundle, state):
        import pandas as pd

        sys.path.insert(0, str(XGBOOST_DIR))
        from deal_score_pipeline import transform_for_inference

        return transform_for_inference(pd.DataFrame([state]), bundle, strict=True)

    @pytest.mark.parametrize("field", sorted(fmt.CATEGORICAL_VALUES))
    def test_every_categorical_value_is_accepted(self, bundle, field):
        for value in fmt.CATEGORICAL_VALUES[field]:
            self._transform(bundle, self._state(**{field: value}))

    @pytest.mark.parametrize("token", fmt.OBJECTION_TOKENS + [fmt.NO_OBJECTIONS])
    def test_every_objection_token_is_accepted(self, bundle, token):
        self._transform(bundle, self._state(main_objections=token))

    def test_multiple_objections_are_accepted(self, bundle):
        joined = fmt.snap_objections(["Price Too High", "Missing Features"])
        self._transform(bundle, self._state(main_objections=joined))

    def test_buying_intent_very_high_really_is_rejected(self, bundle):
        # Guards the comment on INTENT_VALUES. ORDINAL_LEXICON in the pipeline
        # source defines "very high": 4, so reading the source says this is
        # accepted — but the bundle intersects that lexicon with the labels in
        # the training CSV, which never held it. If a retrained bundle ever does
        # accept it, this fails and the value can be added back deliberately.
        sys.path.insert(0, str(XGBOOST_DIR))
        from deal_score_pipeline import SchemaError

        with pytest.raises(SchemaError):
            self._transform(bundle, self._state(buying_intent="Very High"))

    def test_a_fully_defaulted_state_scores(self, bundle):
        # coerce_state's worst case — every field imputed — must still be
        # scoreable, since that is the path a garbled model reply takes.
        state, _ = fmt.coerce_state({})
        self._transform(bundle, state)

    def test_bundle_objection_tokens_match_ours(self, bundle):
        assert {t.lower() for t in fmt.OBJECTION_TOKENS} == set(bundle["objection_tokens"])

    @pytest.mark.parametrize("field", ["customer_requirements", "risk_factors"])
    def test_onehot_values_all_exist_in_the_bundle(self, bundle, field):
        known = {
            c[len(field) + 1:] for c in bundle["onehot_features"] if c.startswith(field + "_")
        }
        ours = set(fmt.CATEGORICAL_VALUES[field])
        assert ours <= known, f"{field}: {sorted(ours - known)} would silently score as absent"


@needs_xgboost_project
class TestStaticContract:
    """Cheap checks that run without xgboost installed."""

    def test_onehot_columns_are_the_ones_we_treat_as_single_valued(self):
        assert set(_module_constant("ONEHOT_COLUMNS")) == {"customer_requirements", "risk_factors"}

    def test_main_objections_is_the_multilabel_column(self):
        assert _module_constant("MULTILABEL_COLUMN") == "main_objections"

    def test_no_objections_sentinel_reads_as_empty(self):
        assert fmt.NO_OBJECTIONS.lower() in _module_constant("NULL_OBJECTION_TOKENS")

    @pytest.mark.parametrize("field", ["customer_requirements", "risk_factors"])
    def test_onehot_vocabulary_matches_training_data(self, field):
        trained = _dataset_values(field)
        assert set(fmt.CATEGORICAL_VALUES[field]) == trained

    def test_relationship_strength_is_numeric_not_categorical(self):
        # The written spec called for Weak/Moderate/Strong/Very Strong.
        # serve_api declares `float = Field(ge=0, le=10)` — a string is a 422
        # before the model is even consulted.
        assert "relationship_strength" not in fmt.CATEGORICAL_VALUES
        assert fmt.NUMERIC_RANGES["relationship_strength"] == (0, 10)
        assert "relationship_strength: float = Field(ge=0, le=10)" in SERVE_API.read_text(
            encoding="utf-8"
        )

    def test_we_emit_exactly_the_seventeen_model_inputs_in_order(self):
        with DATASET.open(encoding="utf-8-sig") as handle:
            columns = [c.strip().lower() for c in next(csv.reader(handle))]
        assert fmt.FIELD_ORDER == [c for c in columns if c != "deal_score"]
        assert len(fmt.FIELD_ORDER) == 17


class TestCoercion:
    def _valid(self):
        return {f: fmt.DEFAULTS[f] for f in fmt.FIELD_ORDER}

    def test_clean_state_needs_no_repairs(self):
        state, repairs = fmt.coerce_state(self._valid())
        assert repairs == []
        assert list(state) == fmt.FIELD_ORDER

    def test_empty_reply_still_yields_a_scoreable_state(self):
        state, repairs = fmt.coerce_state({})
        assert list(state) == fmt.FIELD_ORDER
        assert len(repairs) == 17  # everything imputed, and it says so
        for field, allowed in fmt.CATEGORICAL_VALUES.items():
            assert state[field] in allowed

    @pytest.mark.parametrize(
        "field,given,expected",
        [
            ("customer_sentiment", "positive", "Positive"),
            ("product_interest_level", "very high", "Very High"),
            ("budget_status", "Fully approved", "Fully Approved"),
            ("implementation_readiness", "Partially ready", "Partially Ready"),
            ("meeting_outcome", "proposal sent", "Proposal Sent"),
        ],
    )
    def test_near_misses_snap_to_canonical(self, field, given, expected):
        assert fmt.snap(field, given) == expected

    def test_longest_match_wins(self):
        assert fmt.snap("product_interest_level", "Very High") == "Very High"
        assert fmt.snap("product_interest_level", "High") == "High"

    def test_very_high_intent_degrades_to_high_not_to_the_default(self):
        # The bundle has no "Very High" for buying_intent. A model emitting it
        # anyway should land on the nearest real value — flattening an
        # enthusiastic reading into the neutral "Medium" default would be worse.
        assert fmt.snap("buying_intent", "Very High") == "High"

    def test_unmappable_value_falls_back_and_reports_it(self):
        raw = self._valid()
        raw["customer_sentiment"] = "Ecstatic"
        state, repairs = fmt.coerce_state(raw)
        assert state["customer_sentiment"] == "Neutral"
        assert any("customer_sentiment" in r for r in repairs)

    @pytest.mark.parametrize(
        "field,given,expected",
        [
            ("relationship_strength", 99, 10),
            ("relationship_strength", -5, 0),
            ("lead_score", 150, 100),
            ("engagement_score", "72", 72),
            ("total_meetings", "3", 3),
        ],
    )
    def test_numbers_are_clamped(self, field, given, expected):
        raw = self._valid()
        raw[field] = given
        state, _ = fmt.coerce_state(raw)
        assert state[field] == expected

    def test_objection_list_becomes_a_semicolon_string(self):
        # The pipeline splits on [;,]; a Python list stringifies to
        # "['Price Too High']" and matches nothing.
        result = fmt.snap_objections(["Price Too High", "Missing Features"])
        assert result == "Price Too High; Missing Features"

    def test_untrained_objection_tokens_are_dropped(self):
        # "Integration" and "ROI" come from the written spec; neither is a
        # trained token, and both would score as absent regardless.
        assert fmt.snap_objections("Integration; ROI") == fmt.NO_OBJECTIONS

    @pytest.mark.parametrize("empty", [None, "", "None", "none", "n/a", []])
    def test_empty_objections_become_the_sentinel(self, empty):
        assert fmt.snap_objections(empty) == fmt.NO_OBJECTIONS

    def test_extract_state_ignores_surrounding_prose(self):
        assert fmt.extract_state('Here you go: {"a": 1} hope that helps')["a"] == 1

    def test_extract_state_returns_none_without_an_object(self):
        assert fmt.extract_state("I cannot help with that.") is None
        assert fmt.extract_state("") is None


@pytest.mark.skipif(not TRAIN_JSONL.exists(), reason="dataset not generated yet")
class TestGeneratedDataset:
    """The dataset must be learnable, not merely well-formed.

    Every target field has to be reachable from the input. An earlier version
    drifted lead_score, engagement_score and relationship_strength randomly per
    meeting — well-formed, validated fine, and impossible to learn: nothing in
    the notes implies 49 -> 51. Training on that teaches the model to emit
    plausible-looking jitter, which is the invented movement the prompt forbids.
    """

    @pytest.fixture(scope="class")
    def rows(self):
        with TRAIN_JSONL.open(encoding="utf-8") as handle:
            return [json.loads(line) for line in handle]

    def test_every_row_has_the_training_fields(self, rows):
        for row in rows:
            assert row["instruction"] and row["input"] and row["output"]

    def test_every_target_is_a_complete_valid_state(self, rows):
        for row in rows:
            state = json.loads(row["output"])
            assert list(state) == fmt.FIELD_ORDER
            coerced, repairs = fmt.coerce_state(state)
            assert repairs == [], f"generator emitted a value needing repair: {repairs}"

    def test_lead_score_is_derivable_from_the_state(self, rows):
        from generate_dataset import _lead_score

        for row in rows:
            state = json.loads(row["output"])
            assert _lead_score(state) == state["lead_score"]

    def test_engagement_score_is_derivable_from_the_state(self, rows):
        from generate_dataset import _engagement_score

        for row in rows:
            state = json.loads(row["output"])
            assert _engagement_score(state) == state["engagement_score"]

    def test_total_meetings_always_increments_by_one(self, rows):
        for row in rows:
            previous = fmt.extract_state(row["input"])
            state = json.loads(row["output"])
            assert state["total_meetings"] == previous["total_meetings"] + 1

    def test_most_fields_stay_put_in_a_given_meeting(self, rows):
        """The skill being trained is carrying unchanged fields forward.

        If the average meeting moved most of the state there would be nothing to
        carry, and the model would learn to rewrite everything every time.
        """
        unchanged = 0
        for row in rows:
            previous = fmt.extract_state(row["input"])
            state = json.loads(row["output"])
            unchanged += sum(
                1 for f in fmt.FIELD_ORDER
                if f != "total_meetings" and previous[f] == state[f]
            )
        average = unchanged / len(rows)
        assert average >= 8, f"only {average:.1f} of 16 fields carry forward on average"

    def test_score_distribution_covers_the_range(self, rows):
        scores = [json.loads(r["output"])["lead_score"] for r in rows]
        buckets = {s // 20 for s in scores}
        assert buckets >= {0, 1, 2, 3, 4}, f"lead_score never reaches some bands: {sorted(buckets)}"

    def test_relationship_strength_moves_at_most_one_point(self, rows):
        # It is the one field with memory; a jump would mean trust appearing
        # from nowhere.
        for row in rows:
            previous = fmt.extract_state(row["input"])
            state = json.loads(row["output"])
            assert abs(state["relationship_strength"] - previous["relationship_strength"]) <= 1
