"""Regression tests for the deal-scoring preprocessing contract.

These guard the subtle failures that produce *wrong numbers rather than
errors* - the class of bug that is invisible in production:

* pandas silently reading the literal string ``"None"`` as NaN, which erased
  every "no objections" row and let imputation invent an objection;
* late-binding closures in the ordinal-encoding loops;
* inference encoding a category differently than training did;
* schema drift being repaired silently instead of rejected.

Run with::

    python -m pytest tests/ -q
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from deal_score_pipeline import (
    NA_VALUES,
    ORDINAL_LEXICON,
    PIPELINE_VERSION,
    SchemaError,
    build_ordinal_maps,
    discover_objection_tokens,
    engineer_features,
    load_bundle,
    normalise_label,
    priority_band,
    safe_mape,
    slugify,
    transform_for_inference,
)

MODEL_PATH = Path(__file__).resolve().parents[1] / "outputs" / "xgboost_deal_score.pkl"

#: A complete, valid CRM record in the v3 vocabulary - the baseline the strict
#: validation tests mutate one field at a time.
VALID_RECORD = {
    "total_meetings": 9,
    "lead_score": 87.3,
    "customer_sentiment": "Positive",
    "buying_intent": "High",
    "relationship_strength": 8,
    "budget_status": "Fully Approved",
    "decision_maker_involvement": "Yes",
    "customer_urgency": "High",
    "main_objections": "No Objections",
    "product_interest_level": "High",
    "meeting_outcome": "Verbal Agreement",
    "customer_requirements": "Scalable Infrastructure",
    "risk_factors": "No Risk Identified",
    "competitor_mention": "No",
    "engagement_score": 27.4,
    "implementation_readiness": "Fully Ready",
    "upsell_opportunity": "Yes",
}


# --------------------------------------------------------------------------- #
# The "None" bug - highest-value test in this file
# --------------------------------------------------------------------------- #

def test_literal_none_is_not_read_as_missing(tmp_path: Path) -> None:
    """'None' in main_objections means "no objections", not a null."""
    csv = tmp_path / "sample.csv"
    csv.write_text("main_objections,x\nNone,1\nPrice Too High,2\n", encoding="utf-8")

    frame = pd.read_csv(csv, keep_default_na=False, na_values=list(NA_VALUES))
    assert frame["main_objections"].isna().sum() == 0
    assert frame.loc[0, "main_objections"] == "None"


def test_none_produces_zero_objections() -> None:
    """A 'None' row must count as 0 objections, not be imputed to something."""
    assert discover_objection_tokens(pd.Series(["None"])) == []
    tokens = discover_objection_tokens(pd.Series(["None", "Price Too High; Budget Not Allocated"]))
    assert tokens == ["budget not allocated", "price too high"]


# --------------------------------------------------------------------------- #
# Multi-label objection parsing
# --------------------------------------------------------------------------- #

@pytest.mark.parametrize("raw,expected", [
    ("Price Too High; Budget Not Allocated", 2),   # spec separator
    ("Budget, Timeline", 2),                        # legacy separator
    ("Price Too High", 1),
    ("No Objections", 0),
    ("", 0),
])
def test_objection_separators(raw: str, expected: int) -> None:
    """Both ';' and ',' separators are supported."""
    assert len(discover_objection_tokens(pd.Series([raw]))) == expected


def test_slugify_handles_slashes_and_hyphens() -> None:
    assert slugify("Security / Compliance Concerns") == "security_compliance_concerns"
    assert slugify("Lack of Internal Buy-in") == "lack_of_internal_buy_in"


def test_normalise_label_is_whitespace_and_case_insensitive() -> None:
    assert normalise_label("  Fully   Approved ") == normalise_label("fully approved")


# --------------------------------------------------------------------------- #
# Ordinal encoding
# --------------------------------------------------------------------------- #

def test_ordinal_maps_are_monotonic_in_business_order() -> None:
    """Encoding must preserve worse -> better ordering, or the model is misled."""
    frame = pd.DataFrame({
        "customer_sentiment": ["Negative", "Neutral", "Positive"],
        "customer_urgency": ["Low", "Medium", "High"],
    })
    maps = build_ordinal_maps(frame)
    assert maps["customer_sentiment"]["negative"] < maps["customer_sentiment"]["neutral"]
    assert maps["customer_sentiment"]["neutral"] < maps["customer_sentiment"]["positive"]
    assert maps["customer_urgency"]["low"] < maps["customer_urgency"]["high"]


def test_numeric_column_is_not_ordinal_encoded() -> None:
    """relationship_strength arrives numeric in v2/v3 and must stay untouched."""
    frame = pd.DataFrame({"relationship_strength": [1, 5, 10]})
    assert "relationship_strength" not in build_ordinal_maps(frame)


def test_lexicon_covers_both_vocabularies() -> None:
    """Both the legacy and spec wordings must map to the same ordering."""
    budget = ORDINAL_LEXICON["budget_status"]
    assert budget["not available"] == budget["not allocated"]      # worst
    assert budget["confirmed"] == budget["fully approved"]         # best
    assert budget["not allocated"] < budget["fully approved"]


def test_no_loop_variable_leakage_across_columns() -> None:
    """Guards the late-binding-closure bug: each column uses its own map."""
    frame = pd.DataFrame({
        "customer_sentiment": ["Positive"],
        "customer_urgency": ["Low"],
        "buying_intent": ["High"],
    })
    maps = build_ordinal_maps(frame)
    assert maps["customer_sentiment"]["positive"] == 2
    assert maps["customer_urgency"]["low"] == 1
    assert maps["buying_intent"]["high"] == 3


# --------------------------------------------------------------------------- #
# Metrics
# --------------------------------------------------------------------------- #

def test_safe_mape_excludes_zero_actuals() -> None:
    """MAPE is undefined at zero; those rows are excluded, not fudged."""
    mape, excluded = safe_mape(np.array([0.0, 50.0]), np.array([10.0, 55.0]))
    assert excluded == 1
    assert mape == pytest.approx(10.0)


def test_safe_mape_all_zero_actuals_is_nan() -> None:
    mape, excluded = safe_mape(np.array([0.0, 0.0]), np.array([1.0, 2.0]))
    assert excluded == 2
    assert np.isnan(mape)


@pytest.mark.parametrize("score,band", [
    (95.0, "HIGH"), (75.0, "HIGH"), (74.9, "MEDIUM"),
    (50.0, "MEDIUM"), (49.9, "LOW"), (25.0, "LOW"), (24.9, "VERY LOW"), (0.0, "VERY LOW"),
])
def test_priority_band_boundaries(score: float, band: str) -> None:
    assert priority_band(score)[0] == band


# --------------------------------------------------------------------------- #
# Train -> save -> load -> predict round trip
# --------------------------------------------------------------------------- #

@pytest.mark.skipif(not MODEL_PATH.exists(), reason="no trained model available")
class TestTrainedModel:
    """Contract tests against the actual saved bundle."""

    @staticmethod
    @pytest.fixture(scope="class")
    def bundle() -> dict:
        return load_bundle(MODEL_PATH)

    def test_bundle_carries_provenance(self, bundle: dict) -> None:
        prov = bundle["provenance"]
        assert prov["pipeline_version"] == PIPELINE_VERSION
        assert prov["trained_at"]
        assert prov["source_rows"] > 0
        assert "xgboost" in prov["library_versions"]

    def test_version_mismatch_is_refused(self, bundle: dict, tmp_path: Path) -> None:
        """A bundle from another contract must not silently serve."""
        import joblib
        stale = dict(bundle)
        stale["provenance"] = {**bundle["provenance"], "pipeline_version": "0.0.1-old"}
        path = tmp_path / "stale.pkl"
        joblib.dump(stale, path)

        with pytest.raises(SchemaError, match="pipeline version"):
            load_bundle(path)
        assert load_bundle(path, require_version=False)  # explicit opt-out works

    def test_inference_matches_training_encoding(self, bundle: dict) -> None:
        """A record must encode to exactly the training feature schema."""
        record = pd.DataFrame([{
            "total_meetings": 9, "lead_score": 87.3,
            "customer_sentiment": "Positive", "buying_intent": "High",
            "relationship_strength": 8, "budget_status": "Fully Approved",
            "decision_maker_involvement": "Yes", "customer_urgency": "High",
            "main_objections": "No Objections", "product_interest_level": "High",
            "meeting_outcome": "Verbal Agreement",
            "customer_requirements": "Scalable Infrastructure",
            "risk_factors": "No Risk Identified", "competitor_mention": "No",
            "engagement_score": 27.4, "implementation_readiness": "Fully Ready",
            "upsell_opportunity": "Yes",
        }])
        features = transform_for_inference(record, bundle)
        assert list(features.columns) == bundle["feature_names"]
        assert not features.isna().any().any()
        assert features.loc[0, "num_objections"] == 0

    def test_strict_mode_rejects_unknown_category(self, bundle: dict) -> None:
        """A complete record with one bad value must be refused, not imputed."""
        record = pd.DataFrame([{**VALID_RECORD, "customer_sentiment": "Ecstatic"}])
        with pytest.raises(SchemaError, match="unrecognised value"):
            transform_for_inference(record, bundle, strict=True)

    def test_strict_mode_rejects_missing_input_field(self, bundle: dict) -> None:
        """Omitting a required CRM field must be refused, not silently zeroed."""
        record = pd.DataFrame([{k: v for k, v in VALID_RECORD.items()
                                if k != "budget_status"}])
        with pytest.raises(SchemaError, match="missing required input field"):
            transform_for_inference(record, bundle, strict=True)

    def test_strict_mode_allows_partial_onehot(self, bundle: dict) -> None:
        """One-hot dummies for other categories are correctly absent, not drift."""
        out = transform_for_inference(pd.DataFrame([VALID_RECORD]), bundle, strict=True)
        assert list(out.columns) == bundle["feature_names"]

    def test_lenient_mode_imputes_instead_of_raising(self, bundle: dict) -> None:
        record = pd.DataFrame([{"customer_sentiment": "Ecstatic"}])
        out = transform_for_inference(record, bundle, strict=False)
        assert list(out.columns) == bundle["feature_names"]

    def test_predictions_are_finite_and_ordered(self, bundle: dict) -> None:
        """A strong deal must outscore a weak one - basic sanity of direction."""
        base = {
            "total_meetings": 1, "lead_score": 5.0, "customer_sentiment": "Negative",
            "buying_intent": "Low", "relationship_strength": 1,
            "budget_status": "Not Allocated", "decision_maker_involvement": "No",
            "customer_urgency": "Low",
            "main_objections": "Price Too High; Budget Not Allocated",
            "product_interest_level": "Low", "meeting_outcome": "No Show / Cancelled",
            "customer_requirements": "Basic Feature Set",
            "risk_factors": "Budget Constraints", "competitor_mention": "Yes",
            "engagement_score": 2.0, "implementation_readiness": "Not Ready",
            "upsell_opportunity": "No",
        }
        strong = {**base,
                  "total_meetings": 12, "lead_score": 95.0,
                  "customer_sentiment": "Positive", "buying_intent": "High",
                  "relationship_strength": 10, "budget_status": "Fully Approved",
                  "decision_maker_involvement": "Yes", "customer_urgency": "Critical",
                  "main_objections": "No Objections",
                  "product_interest_level": "Very High",
                  "meeting_outcome": "Verbal Agreement",
                  "risk_factors": "No Risk Identified", "competitor_mention": "No",
                  "engagement_score": 95.0, "implementation_readiness": "Fully Ready",
                  "upsell_opportunity": "Yes"}

        features = transform_for_inference(pd.DataFrame([base, strong]), bundle)
        preds = bundle["model"].predict(features)
        assert np.isfinite(preds).all()
        assert preds[1] > preds[0], "strong deal should outscore weak deal"


# --------------------------------------------------------------------------- #
# End-to-end feature engineering
# --------------------------------------------------------------------------- #

def test_engineer_features_produces_all_numeric() -> None:
    """Nothing non-numeric may survive into the model matrix."""
    frame = pd.DataFrame({
        "total_meetings": [5, 8],
        "lead_score": [50.0, 90.0],
        "customer_sentiment": ["Neutral", "Positive"],
        "buying_intent": ["Medium", "High"],
        "relationship_strength": [4, 9],
        "budget_status": ["Under Review", "Fully Approved"],
        "decision_maker_involvement": ["Indirect", "Yes"],
        "customer_urgency": ["Medium", "Critical"],
        "main_objections": ["Price Too High", "No Objections"],
        "product_interest_level": ["Medium", "Very High"],
        "meeting_outcome": ["Rescheduled", "Verbal Agreement"],
        "customer_requirements": ["Basic Feature Set", "Standard Package"],
        "risk_factors": ["Budget Constraints", "No Risk Identified"],
        "competitor_mention": ["Yes", "No"],
        "engagement_score": [30.0, 85.0],
        "implementation_readiness": ["Not Ready", "Fully Ready"],
        "upsell_opportunity": ["No", "Yes"],
        "deal_score": [30, 90],
    })
    engineered, maps, tokens, _, scaler = engineer_features(frame)

    assert all(pd.api.types.is_numeric_dtype(engineered[c]) for c in engineered.columns)
    assert "main_objections" not in engineered.columns
    assert engineered["num_objections"].tolist() == [1, 0]
    assert "relationship_strength" not in maps      # numeric, left alone
    assert tokens == ["price too high"]
    assert scaler is not None
