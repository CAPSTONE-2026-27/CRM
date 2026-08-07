"""
evaluate_end_to_end.py
======================
Measures what the LLM's extraction errors actually cost, in deal-score points.

Field accuracy answers "how often is the model right about a field". It does not
answer "how wrong is the final number", and those come apart badly: a field can
be wrong and barely move the deal score, or be wrong in a way that moves it 20
points. Only the regressor knows which.

So this runs the real chain twice per held-out example:

    ground-truth state  -> XGBoost -> reference deal score
    model's state       -> XGBoost -> predicted deal score

and reports the difference. That is the number a sales director would care
about — not whether field 11 was right, but whether the deal came out scored
the same.

Run:
    python scripts/evaluate_end_to_end.py --limit 30
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import deal_state_format as fmt  # noqa: E402
from evaluate import generate, held_out_rows, load  # noqa: E402
from train import OUTPUT_DIR  # noqa: E402

XGBOOST_DIR = Path(__file__).resolve().parent.parent.parent / "XgBoost"


def load_scorer():
    """The real deal-score bundle, in strict mode — the same path production uses."""
    import joblib
    import pandas as pd  # noqa: F401  (imported for the caller)

    sys.path.insert(0, str(XGBOOST_DIR))
    from deal_score_pipeline import transform_for_inference  # noqa: E402

    bundles = sorted((XGBOOST_DIR / "models").glob("*.pkl"))
    if not bundles:
        raise FileNotFoundError(f"No model bundle in {XGBOOST_DIR / 'models'}")
    bundle = joblib.load(bundles[-1])
    print(f"scorer bundle: {bundles[-1].name}")

    def score(state: dict) -> float:
        import pandas as pd

        features = transform_for_inference(pd.DataFrame([state]), bundle, strict=True)
        return float(bundle["model"].predict(features)[0])

    return score


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adapter", type=Path, default=OUTPUT_DIR)
    parser.add_argument("--limit", type=int, default=30)
    args = parser.parse_args()

    score = load_scorer()
    rows = held_out_rows(args.limit)
    print(f"Running {len(rows)} held-out examples through LLM -> XGBoost...\n")

    tokenizer, model = load(args.adapter)

    deltas = []
    band_matches = 0
    rejected = 0

    def band(value: float) -> str:
        # Coarse buckets are what a rep actually acts on; a 3-point error inside
        # one band changes nothing, a 3-point error across a boundary does.
        if value < 34:
            return "Low"
        if value < 67:
            return "Medium"
        return "High"

    for index, row in enumerate(rows, 1):
        messages = row["messages"]
        expected = json.loads(messages[2]["content"])

        reply = generate(tokenizer, model, messages[:2])
        raw = fmt.extract_state(reply)
        actual, _ = fmt.coerce_state(raw or {})

        try:
            reference = score(expected)
            predicted = score(actual)
        except Exception as exc:  # noqa: BLE001
            rejected += 1
            print(f"  [{index:3d}] scorer rejected: {exc}")
            continue

        delta = predicted - reference
        deltas.append(delta)
        same_band = band(reference) == band(predicted)
        band_matches += same_band
        print(f"  [{index:3d}] reference {reference:5.1f}  predicted {predicted:5.1f}  "
              f"delta {delta:+6.1f}  {'' if same_band else '<- band differs'}")

    if not deltas:
        print("\nNo examples scored.")
        return 1

    n = len(deltas)
    absolute = sorted(abs(d) for d in deltas)
    mae = sum(absolute) / n
    bias = sum(deltas) / n
    within = lambda t: sum(1 for a in absolute if a <= t) / n  # noqa: E731

    print("\n" + "=" * 62)
    print(f"examples scored        {n}" + (f"  ({rejected} rejected)" if rejected else ""))
    print(f"mean absolute error    {mae:.2f} deal-score points")
    print(f"median absolute error  {absolute[n // 2]:.2f}")
    print(f"worst case             {absolute[-1]:.2f}")
    # Bias matters separately from MAE: errors that cancel out leave the
    # pipeline unbiased overall, while a consistent skew would quietly inflate
    # or deflate every deal in the CRM.
    print(f"mean signed error      {bias:+.2f}  (bias)")
    print(f"within  1 point        {within(1):6.1%}")
    print(f"within  3 points       {within(3):6.1%}")
    print(f"within  5 points       {within(5):6.1%}")
    print(f"same band (Low/Med/High) {band_matches}/{n}  {band_matches / n:6.1%}")
    print("=" * 62)
    return 0


if __name__ == "__main__":
    sys.exit(main())
