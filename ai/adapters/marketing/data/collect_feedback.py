"""
collect_feedback.py
===================

Turns the Marketing Head's decisions in the CRM database into training rows for
the marketing adapter.

    python adapters/marketing/data/collect_feedback.py          # needs CRM_FEEDBACK_DB_URL

CRM_FEEDBACK_DB_URL is a libpq connection string for the CRM's PostgreSQL
database, e.g. postgresql://USER:PASS@HOST/neondb?sslmode=require

What counts as a confirmed answer
---------------------------------
Only rows where the model was actually called (ai_input is set, V33 migration)
and a human made a decision:

  Lead evaluation (mkt_campaign_leads)
    * override_eligibility set      -> the model was wrong. Target = the human's
                                       verdict, the reviewer's mandatory note as
                                       the reason, score moved into that verdict's
                                       band (eligible >= 60, not eligible < 60,
                                       as the system prompt asks).
    * APPROVED an eligible lead /   -> the model was right. Target = what the
      REJECTED a not-eligible one      CRM stored, score kept in band.
    * anything else (approving a not-eligible lead, rejecting an eligible one
      without an override, pending, removed) is ambiguous and skipped.

  Content (mkt_content_variants)
    * APPROVED and AI_GENERATED     -> confirmed copy.
    * APPROVED and HUMAN_EDITED     -> corrected copy: the edited text is the target.
    Channels approved from the same model call become one example, with the
    prompt's channel list narrowed to them. Unedited template fallbacks
    (SYSTEM_GENERATED) are skipped.

Every target is run through the same validators the backend uses
(prompt_format.parse_lead_evaluation / check_content_reply); a row that would be
rejected in production is not taught.

Outputs (rewritten in full each run; the database is the source of truth, so a
re-reviewed lead simply replaces its old example):
  data/feedback.jsonl        training rows
  data/benchmark.jsonl       data/benchmark_seed.jsonl + ~20% of feedback rows,
                             never trained on; tools/retrain.py scores both
                             adapters on it
  data/benchmark_seed.jsonl  created once from weights/heldout.jsonl, the rows
                             the current adapter never saw

Which feedback rows go to the benchmark is decided by a hash of the row's
reference, so a row never moves between train and benchmark across runs.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
from pathlib import Path
from typing import Optional

DATA_DIR = Path(__file__).resolve().parent
ADAPTER_DIR = DATA_DIR.parent
sys.path.insert(0, str(ADAPTER_DIR.parent.parent))
from adapters.marketing import prompt_format as fmt  # noqa: E402
from adapters.marketing import rules  # noqa: E402

FEEDBACK_PATH = DATA_DIR / "feedback.jsonl"
BENCHMARK_PATH = DATA_DIR / "benchmark.jsonl"
BENCHMARK_SEED_PATH = DATA_DIR / "benchmark_seed.jsonl"
HELDOUT_PATH = ADAPTER_DIR / "weights" / "heldout.jsonl"

BENCHMARK_PERCENT = 20
# The system prompt's bands: "An eligible lead should normally score 60 or more;
# a not_eligible lead should normally score below 60."
ELIGIBLE_MIN_SCORE = 60
# The model's own confidence on an answer a human overturned says nothing about
# the corrected answer; a fixed, fairly sure value is used instead.
CORRECTED_CONFIDENCE = 0.8
DEFAULT_CONFIDENCE = 0.8

LEAD_SQL = """
    SELECT id, ai_input, ai_score, ai_eligibility, ai_confidence, positive_factors,
           negative_factors, ai_reason, approval_status, override_eligibility, review_note
    FROM mkt_campaign_leads
    WHERE evaluation_source = 'AI_MODEL'
      AND ai_input IS NOT NULL
      AND approval_status IN ('APPROVED', 'REJECTED')
      AND reviewed_at IS NOT NULL
    ORDER BY id
"""

CONTENT_SQL = """
    SELECT id, channel, fields_json, provenance, ai_input
    FROM mkt_content_variants
    WHERE status = 'APPROVED'
      AND ai_input IS NOT NULL
      AND provenance IN ('AI_GENERATED', 'HUMAN_EDITED')
    ORDER BY id
"""


# --------------------------------------------------------------------------
# LEAD EVALUATION
# --------------------------------------------------------------------------
def _json_list(text) -> list:
    if not text:
        return []
    try:
        value = json.loads(text)
    except (TypeError, json.JSONDecodeError):
        return []
    return [v for v in value if isinstance(v, str)] if isinstance(value, list) else []


def lead_verdict(row: dict) -> Optional[tuple[bool, bool]]:
    """(eligible, corrected) for a reviewed row, or None when the decision is ambiguous."""
    override = row.get("override_eligibility")
    if override in ("ELIGIBLE", "NOT_ELIGIBLE"):
        return override == "ELIGIBLE", True
    ai, status = row.get("ai_eligibility"), row.get("approval_status")
    if status == "APPROVED" and ai == "ELIGIBLE":
        return True, False
    if status == "REJECTED" and ai == "NOT_ELIGIBLE":
        return False, False
    return None


def lead_example(row: dict) -> Optional[dict]:
    """One training row from a reviewed mkt_campaign_leads row, or None to skip it."""
    verdict = lead_verdict(row)
    if verdict is None or row.get("ai_score") is None:
        return None
    eligible, corrected = verdict
    try:
        doc = json.loads(row["ai_input"])
        lead_ref = str(doc["lead"]["lead_id"])
        rule_doc = doc.get("eligibility_rules") or {}
    except (TypeError, KeyError, json.JSONDecodeError):
        return None

    if corrected:
        reason = (row.get("review_note") or "").strip()
        confidence = CORRECTED_CONFIDENCE
    else:
        reason = (row.get("ai_reason") or "").strip()
        confidence = row.get("ai_confidence")
        if not isinstance(confidence, (int, float)) or not 0 <= confidence <= 1:
            confidence = DEFAULT_CONFIDENCE
    if not reason:
        return None

    score = int(row["ai_score"])
    score = max(score, ELIGIBLE_MIN_SCORE) if eligible else min(score, ELIGIBLE_MIN_SCORE - 1)

    target = {
        "lead_id": lead_ref,
        "score": score,
        "eligibility": "eligible" if eligible else "not_eligible",
        "confidence": round(float(confidence), 2),
        "positive_factors": _json_list(row.get("positive_factors")),
        "negative_factors": _json_list(row.get("negative_factors")),
        "reason": reason,
    }
    reply = fmt.render_target(target)
    try:
        fmt.parse_lead_evaluation(reply, lead_ref)
    except ValueError:
        return None

    mode = rule_doc.get("match") or "ALL"
    outcomes = [{"outcome": r.get("system_check")} for r in rule_doc.get("rules") or []]
    return {
        "task": "lead_evaluation",
        "messages": [
            {"role": "system", "content": fmt.LEAD_SYSTEM_PROMPT},
            {"role": "user", "content": row["ai_input"]},
            {"role": "assistant", "content": reply},
        ],
        "meta": json.dumps({
            "combined": rules.combine(outcomes, mode), "mode": mode,
            "eligible": target["eligibility"], "score": score,
            "source": "feedback", "ref": f"lead:{row['id']}", "corrected": corrected,
        }),
    }


# --------------------------------------------------------------------------
# CONTENT
# --------------------------------------------------------------------------
def content_examples(rows: list[dict]) -> list[dict]:
    """Training rows from approved content variants, one per model call."""
    by_prompt: dict[str, list[dict]] = {}
    for row in rows:
        by_prompt.setdefault(row["ai_input"], []).append(row)

    examples = []
    for prompt, group in by_prompt.items():
        try:
            doc = json.loads(prompt)
            channel_doc = doc["channels"]
            grounding = fmt.grounding_text(doc["brief"], doc["style"])
        except (TypeError, KeyError, json.JSONDecodeError):
            continue

        fields_by_channel, edited = {}, False
        for row in group:
            key = str(row["channel"]).lower()
            if key not in channel_doc:
                continue
            try:
                fields = json.loads(row["fields_json"])
            except (TypeError, json.JSONDecodeError):
                continue
            if isinstance(fields, dict) and fields:
                fields_by_channel[key] = fields
                edited = edited or row.get("provenance") == "HUMAN_EDITED"
        # Keep the prompt's channel order, and only channels the backend's
        # validator would accept.
        channels = [ch for ch in channel_doc if ch in fields_by_channel]
        if not channels:
            continue
        accepted, _ = fmt.check_content_reply(
            fmt.render_target({ch: fields_by_channel[ch] for ch in channels}), channels, grounding)
        channels = [ch for ch in channels if ch in accepted]
        if not channels:
            continue

        narrowed = dict(doc)
        narrowed["channels"] = {ch: channel_doc[ch] for ch in channels}
        if isinstance(doc.get("variant"), dict) and isinstance(doc["variant"].get("variant_a"), dict):
            narrowed["variant"] = dict(doc["variant"])
            narrowed["variant"]["variant_a"] = {
                ch: v for ch, v in doc["variant"]["variant_a"].items() if ch in channels}
        target = {ch: fields_by_channel[ch] for ch in channels}
        examples.append({
            "task": "content_generation",
            "messages": [
                {"role": "system", "content": fmt.CONTENT_SYSTEM_PROMPT},
                {"role": "user", "content": fmt.to_java_json(narrowed)},
                {"role": "assistant", "content": fmt.render_target(target)},
            ],
            "meta": json.dumps({
                "channels": channels,
                "variant_element": (doc.get("variant") or {}).get("element"),
                "samples": 1, "source": "feedback",
                "ref": "content:" + hashlib.sha1(prompt.encode("utf-8")).hexdigest()[:16],
                "corrected": edited,
            }),
        })
    return examples


# --------------------------------------------------------------------------
# SPLIT AND WRITE
# --------------------------------------------------------------------------
def goes_to_benchmark(example: dict) -> bool:
    """Stable across runs: decided by the row's reference, not its position."""
    ref = json.loads(example["meta"])["ref"]
    return int(hashlib.sha1(ref.encode("utf-8")).hexdigest()[:8], 16) % 100 < BENCHMARK_PERCENT


def read_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.open(encoding="utf-8") if line.strip()]


def write_jsonl(path: Path, rows: list[dict]) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8") as out:
        for row in rows:
            out.write(json.dumps(row, ensure_ascii=False) + "\n")
    tmp.replace(path)


def ensure_benchmark_seed() -> None:
    """Freeze the current adapter's held-out rows the first time, so later
    retrains (which rewrite weights/heldout.jsonl) can't change the yardstick."""
    if BENCHMARK_SEED_PATH.exists():
        return
    if not HELDOUT_PATH.exists():
        raise FileNotFoundError(f"{HELDOUT_PATH} is missing; train the adapter once before collecting feedback.")
    shutil.copyfile(HELDOUT_PATH, BENCHMARK_SEED_PATH)


def build(lead_rows: list[dict], content_rows: list[dict]) -> dict:
    """Write feedback.jsonl and benchmark.jsonl; return counts for the log."""
    ensure_benchmark_seed()
    examples = [e for e in (lead_example(r) for r in lead_rows) if e is not None]
    examples += content_examples(content_rows)

    train, bench = [], []
    for example in examples:
        (bench if goes_to_benchmark(example) else train).append(example)
    write_jsonl(FEEDBACK_PATH, train)
    write_jsonl(BENCHMARK_PATH, read_jsonl(BENCHMARK_SEED_PATH) + bench)

    def count(rows, task):
        return sum(1 for r in rows if r["task"] == task)

    return {
        "lead_rows_read": len(lead_rows), "content_rows_read": len(content_rows),
        "feedback_train": {"lead_evaluation": count(train, "lead_evaluation"),
                           "content_generation": count(train, "content_generation")},
        "feedback_benchmark": {"lead_evaluation": count(bench, "lead_evaluation"),
                               "content_generation": count(bench, "content_generation")},
        "corrected": sum(1 for e in examples if json.loads(e["meta"])["corrected"]),
    }


def fetch(db_url: str) -> tuple[list[dict], list[dict]]:
    import psycopg  # only needed here; the pure functions above are tested without it
    from psycopg.rows import dict_row

    with psycopg.connect(db_url, row_factory=dict_row, connect_timeout=20) as conn:
        conn.read_only = True
        with conn.cursor() as cur:
            cur.execute(LEAD_SQL)
            leads = cur.fetchall()
            cur.execute(CONTENT_SQL)
            content = cur.fetchall()
    return leads, content


def main() -> int:
    parser = argparse.ArgumentParser(description="Build marketing training rows from CRM review decisions.")
    parser.add_argument("--db-url", default=os.getenv("CRM_FEEDBACK_DB_URL"),
                        help="libpq connection string (default: $CRM_FEEDBACK_DB_URL)")
    args = parser.parse_args()
    if not args.db_url:
        print("Set CRM_FEEDBACK_DB_URL (or pass --db-url) to the CRM's PostgreSQL connection string.")
        return 2
    leads, content = fetch(args.db_url)
    print(json.dumps(build(leads, content), indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
