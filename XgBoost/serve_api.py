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
    POST /score    score one record
"""

from __future__ import annotations

import logging
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
    band: str
    action: str
    clipped: bool
    model_version: str


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
        return scorer.score(features.model_dump())
    except SchemaError as exc:
        # The caller sent a value the model has never seen. That is a bad
        # request, not a server fault — surface the scorer's own message, which
        # names the offending column and lists what it accepts.
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:  # pragma: no cover - unexpected serving fault
        log.exception("Scoring failed")
        raise HTTPException(status_code=500, detail="Scoring failed") from exc
