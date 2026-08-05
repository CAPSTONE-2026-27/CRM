"""Serving layer for the CRM deal-scoring model.

This is the module an application should import.  ``deal_score_pipeline`` is
the *training* pipeline; importing it directly from a web request path pulls in
matplotlib, SHAP and the whole training surface.  Everything needed to serve a
prediction lives here.

Design points that matter in production
---------------------------------------
* **The model is loaded once** and cached.  Loading a ~1 MB pickle per request
  is the most common avoidable latency bug in ML services.
* **Input is validated, not silently repaired.**  ``strict=True`` means an
  unrecognised category raises instead of quietly becoming the median - a
  wrong score that looks confident is worse than an error.
* **Every prediction is logged** with the model version that produced it, so a
  score can be explained months later and the model can be retrained on
  real outcomes.

Usage
-----
    from deal_score_service import DealScorer

    scorer = DealScorer()                  # loads outputs/xgboost_deal_score.pkl
    result = scorer.score({...})           # -> {"deal_score": 72.4, "band": ...}
    results = scorer.score_many([{...}])   # batch

CLI smoke test::

    python deal_score_service.py --self-test
"""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Sequence
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from deal_score_pipeline import (
    NA_VALUES,
    SchemaError,
    load_bundle,
    priority_band,
    transform_for_inference,
)

DEFAULT_MODEL = Path(__file__).resolve().parent / "outputs" / "xgboost_deal_score.pkl"
DEFAULT_LOG = Path(__file__).resolve().parent / "outputs" / "prediction_log.jsonl"

SCORE_MIN, SCORE_MAX = 0.0, 100.0


class DealScorer:
    """Loads a model bundle once and scores CRM records against it."""

    def __init__(
        self,
        model_path: Path | str = DEFAULT_MODEL,
        *,
        strict: bool = True,
        log_path: Path | str | None = DEFAULT_LOG,
        require_version: bool = True,
    ) -> None:
        self.model_path = Path(model_path)
        self.bundle = load_bundle(self.model_path, require_version=require_version)
        self.model = self.bundle["model"]
        self.strict = strict
        self.log_path = Path(log_path) if log_path else None

        prov = self.bundle.get("provenance", {})
        self.version = prov.get("pipeline_version", "unknown")
        self.trained_at = prov.get("trained_at", "unknown")
        self.n_features = len(self.bundle["feature_names"])

    # -- introspection ------------------------------------------------------

    def info(self) -> dict[str, Any]:
        """Model metadata - expose this on a ``/health`` endpoint."""
        prov = self.bundle.get("provenance", {})
        metrics = self.bundle.get("metrics", {})
        return {
            "model_path": str(self.model_path),
            "pipeline_version": self.version,
            "trained_at": self.trained_at,
            "source_csv": prov.get("source_csv"),
            "source_rows": prov.get("source_rows"),
            "n_features": self.n_features,
            "test_mae": metrics.get("mae"),
            "test_r2": metrics.get("r2"),
            "strict": self.strict,
        }

    # -- scoring ------------------------------------------------------------

    def score(self, record: dict[str, Any]) -> dict[str, Any]:
        """Score a single CRM record."""
        return self.score_many([record])[0]

    def score_many(self, records: list[dict[str, Any]]) -> list[dict[str, Any]]:
        """Score a batch of records in one pass (far faster than looping)."""
        if not records:
            return []
        return self._score_frame(pd.DataFrame(records))

    def score_csv(self, csv_path: Path | str) -> pd.DataFrame:
        """Score a CSV file, returning the original rows plus predictions."""
        raw = pd.read_csv(csv_path, keep_default_na=False, na_values=list(NA_VALUES))
        scored = self._score_frame(raw)
        out = raw.copy()
        out["predicted_deal_score"] = [r["deal_score"] for r in scored]
        out["priority_band"] = [r["band"] for r in scored]
        out["recommended_action"] = [r["action"] for r in scored]
        return out

    # -- internals ----------------------------------------------------------

    def _score_frame(self, frame: pd.DataFrame) -> list[dict[str, Any]]:
        features = transform_for_inference(frame, self.bundle, strict=self.strict)
        raw_scores = self.model.predict(features)
        clipped = np.clip(raw_scores, SCORE_MIN, SCORE_MAX)

        results = []
        for raw, score in zip(raw_scores, clipped, strict=True):
            band, action = priority_band(float(score))
            results.append({
                "deal_score": round(float(score), 2),
                "band": band,
                "action": action,
                # `clipped` flags scores the model pushed outside 0-100; those
                # are boundary estimates, not precise values.
                "clipped": bool(not SCORE_MIN <= float(raw) <= SCORE_MAX),
                "model_version": self.version,
            })

        self._log(frame, results)
        return results

    def _log(self, frame: pd.DataFrame, results: list[dict[str, Any]]) -> None:
        """Append predictions to a JSONL audit log (never fatal)."""
        if not self.log_path:
            return
        try:
            self.log_path.parent.mkdir(parents=True, exist_ok=True)
            now = datetime.now(timezone.utc).isoformat(timespec="seconds")
            with open(self.log_path, "a", encoding="utf-8") as fh:
                for (_, row), result in zip(frame.iterrows(), results, strict=True):
                    fh.write(json.dumps({
                        "ts": now,
                        "model_version": self.version,
                        "model_trained_at": self.trained_at,
                        "inputs": {k: (None if pd.isna(v) else v) for k, v in row.items()},
                        "prediction": result["deal_score"],
                        "band": result["band"],
                    }, default=str) + "\n")
        except Exception as exc:  # noqa: BLE001 - logging must never break scoring
            print(f"[WARN] prediction logging failed: {type(exc).__name__}: {exc}",
                  file=sys.stderr)


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #

SAMPLE_RECORD = {
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


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Deal-score serving layer")
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--csv", type=Path, help="Score a CSV and print a summary.")
    parser.add_argument("--self-test", action="store_true",
                        help="Score a built-in sample record and print model info.")
    parser.add_argument("--no-strict", action="store_true",
                        help="Impute unknown categories instead of rejecting them.")
    args = parser.parse_args(argv)

    scorer = DealScorer(args.model, strict=not args.no_strict)
    print(json.dumps(scorer.info(), indent=2))

    if args.self_test:
        print("\n--- sample record ---")
        print(json.dumps(scorer.score(SAMPLE_RECORD), indent=2))
        print("\n--- strict-mode rejection check ---")
        bad = {**SAMPLE_RECORD, "customer_sentiment": "Ecstatic"}
        try:
            scorer.score(bad)
            print("  FAIL: invalid category was accepted")
            return 1
        except SchemaError as exc:
            print(f"  OK: rejected -> {exc}")

    if args.csv:
        out = scorer.score_csv(args.csv)
        print(f"\nScored {len(out)} rows from {args.csv}")
        print(out["priority_band"].value_counts().to_string())
    return 0


if __name__ == "__main__":
    sys.exit(main())
