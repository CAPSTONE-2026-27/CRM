"""Tests for the HTTP layer in serve_api.py.

The handlers are called directly rather than through a TestClient: the bug
class worth pinning here is what the handlers read out of the model bundle, not
the HTTP plumbing, and it keeps httpx out of the dependency set.

Run with::

    python -m pytest tests/ -q
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

MODEL_PATH = Path(__file__).resolve().parents[1] / "outputs" / "xgboost_deal_score.pkl"

pytestmark = pytest.mark.skipif(not MODEL_PATH.exists(), reason="no trained model bundle")


def test_health_reports_the_bundles_test_r2() -> None:
    """The metrics live at bundle["metrics"], not inside provenance.

    Reading them from provenance returned null for every request, so a health
    check could never show which model quality was actually being served.
    """
    import serve_api

    health = serve_api.health()

    assert health["status"] == "UP"
    assert health["test_r2"] is not None
    assert health["test_r2"] == serve_api.scorer.bundle["metrics"]["r2"]
