"""HTTP serving layer for the deal-scoring model.

``deal_score_service.DealScorer`` is the library; this exposes it over HTTP so
the Spring backend can score a deal without embedding a Python runtime. It is
deliberately thin — validation, encoding and logging all stay in the scorer,
which is what the model was tested against.

Run:
    ./.venv/bin/uvicorn serve_api:app --port 8000

Endpoints:
    GET  /health   liveness plus the loaded model's provenance
    GET  /schema   the accepted values for every input, read from the bundle
    POST /score    score one record, with a calibrated win probability
"""

from __future__ import annotations

import logging
import math
from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from deal_score_pipeline import SchemaError
from deal_score_service import DealScorer

log = logging.getLogger("deal_score_api")

app = FastAPI(title="TechCRM deal scoring", version="1.0.0")

# Loaded once at import. A ~1 MB pickle per request is the classic avoidable
# latency bug in a model service, and it would also re-read the bundle's
# encoders on every call.
scorer = DealScorer()


class DealFeatures(BaseModel):
    """The 17 inputs the model was trained on.

    Values are passed through untouched: the scorer runs in strict mode, so an
    unrecognised category raises rather than silently becoming the median. A
    confidently wrong score is worse than an error.
    """

    total_meetings: int = Field(ge=0)
    lead_score: float = Field(ge=0, le=100)
    customer_sentiment: str
    buying_intent: str
    relationship_strength: float = Field(ge=0, le=10)
    budget_status: str
    decision_maker_involvement: str
    customer_urgency: str
    main_objections: str
    product_interest_level: str
    meeting_outcome: str
    customer_requirements: str
    risk_factors: str
    competitor_mention: str
    engagement_score: float = Field(ge=0, le=100)
    implementation_readiness: str
    upsell_opportunity: str


class ScoreResponse(BaseModel):
    deal_score: float
    win_probability: float
    band: str
    action: str
    clipped: bool
    model_version: str


# Slope chosen so a score of 75 maps to a 0.75 probability, which is where the
# HIGH band begins — the two therefore agree instead of telling the sales team
# different stories about the same deal. ln(3) / 25 is what makes that exact.
_WIN_PROBABILITY_SLOPE = math.log(3) / 25


def win_probability(deal_score: float) -> float:
    """Convert a deal score into a win probability.

    This is a calibration of the regressor's output, NOT a separately trained
    classifier. The bundle was fitted to predict a 0-100 deal score; no win/loss
    label was ever part of its training data, so there is no learned probability
    to read off. A monotonic squash of the score is the honest thing to expose:
    it reorders nothing and adds no information the score did not already carry.

    A logistic rather than ``score / 100`` because the tails should not be
    taken literally. A model that outputs 97 has not found a deal that closes 97
    times out of 100; it has found one that looks like the best deals it was
    trained on. The sigmoid compresses that to ~0.93 and floors a score of 0 at
    ~0.10, which is closer to how these deals actually resolve.

    Replace this function if a win/loss-labelled model is ever trained — that is
    the only thing that would make a real probability available.
    """
    return round(1 / (1 + math.exp(-_WIN_PROBABILITY_SLOPE * (deal_score - 50))), 4)


@app.get("/health")
def health() -> dict[str, Any]:
    provenance = scorer.bundle.get("provenance", {})
    return {
        "status": "UP",
        "model_version": scorer.version,
        "trained_at": provenance.get("trained_at"),
        "test_r2": provenance.get("metrics", {}).get("r2"),
    }


@app.get("/schema")
def schema() -> dict[str, Any]:
    """The accepted values, read from the model bundle rather than hardcoded.

    The form and the model therefore cannot drift apart: whatever the bundle
    was trained with is what the UI offers.
    """
    bundle = scorer.bundle
    ordinal = {
        column: sorted(mapping, key=lambda label: mapping[label])
        for column, mapping in bundle["ordinal_maps"].items()
    }

    onehot: dict[str, list[str]] = {}
    for feature in bundle["onehot_features"]:
        column, _, value = feature.partition("_")
        # Column names themselves contain underscores, so split on the known
        # prefixes rather than the first underscore.
        for known in ("customer_requirements", "risk_factors"):
            if feature.startswith(known + "_"):
                onehot.setdefault(known, []).append(feature[len(known) + 1 :])
                break

    return {
        "model_version": scorer.version,
        "numeric": {
            "total_meetings": {"min": 0, "max": 100, "step": 1},
            "lead_score": {"min": 0, "max": 100, "step": 1},
            "relationship_strength": {"min": 0, "max": 10, "step": 1},
            "engagement_score": {"min": 0, "max": 100, "step": 1},
        },
        "ordinal": ordinal,
        "onehot": {k: sorted(v) for k, v in onehot.items()},
        "objection_tokens": sorted(bundle["objection_tokens"]),
    }


@app.post("/score", response_model=ScoreResponse)
def score(features: DealFeatures) -> dict[str, Any]:
    try:
        result = scorer.score(features.model_dump())
        return {**result, "win_probability": win_probability(result["deal_score"])}
    except SchemaError as exc:
        # The caller sent a value the model has never seen. That is a bad
        # request, not a server fault — surface the scorer's own message, which
        # names the offending column and lists what it accepts.
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:  # pragma: no cover - unexpected serving fault
        log.exception("Scoring failed")
        raise HTTPException(status_code=500, detail="Scoring failed") from exc


if __name__ == "__main__":
    # Lets the service be started with `python serve_api.py`, not only via a
    # uvicorn command line — the model is loaded in-process either way, so an
    # external launcher buys nothing and is one more thing to remember.
    import os

    import uvicorn

    uvicorn.run(
        app,
        host=os.environ.get("DEAL_SCORER_HOST", "127.0.0.1"),
        port=int(os.environ.get("DEAL_SCORER_PORT", "8000")),
        log_level="info",
    )
