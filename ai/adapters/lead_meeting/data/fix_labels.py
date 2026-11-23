"""
fix_labels.py
=============
Applies data/label_fixes.json to data/train.jsonl: rewrites customer_sentiment
in `signals` and the derived meeting score / priority in `meta`, then
re-renders every row's `messages` from prompt_format -- train.py trains on that
column, so a SYSTEM_PROMPT edit only reaches the model through this step.
Idempotent -- running it twice changes nothing the second time.

Run:
    python adapters/lead_meeting/data/fix_labels.py
    python adapters/lead_meeting/data/fix_labels.py --check   # report only
"""

from __future__ import annotations

import argparse
import collections
import json
import sys
from pathlib import Path

ADAPTER_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ADAPTER_DIR))
import prompt_format as fmt  # noqa: E402

DATA_PATH = ADAPTER_DIR / "data" / "train.jsonl"
FIXES_PATH = ADAPTER_DIR / "data" / "label_fixes.json"


def apply(record: dict, sentiment: str) -> dict:
    signals = dict(record["signals"], customer_sentiment=sentiment)
    score = fmt.meeting_score(signals)
    record["signals"] = signals
    record["meta"] = dict(record.get("meta") or {}, meeting_score=score,
                          priority=fmt.priority_for(score))
    return record


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="report without writing")
    args = parser.parse_args()

    fixes = json.loads(FIXES_PATH.read_text(encoding="utf-8"))["fixes"]
    rows = [json.loads(line) for line in DATA_PATH.open(encoding="utf-8") if line.strip()]
    by_id = {r["meeting_id"]: r for r in rows}

    missing = [mid for mid in fixes if mid not in by_id]
    if missing:
        print(f"{len(missing)} fix ids not in {DATA_PATH.name}: {missing[:5]}")
        return 1

    changed = collections.Counter()
    for mid, (sentiment, _reason) in fixes.items():
        if sentiment not in fmt.SENTIMENT:
            raise SystemExit(f"{mid}: {sentiment!r} is not a sentiment value")
        before = by_id[mid]["signals"]["customer_sentiment"]
        if before != sentiment:
            changed[f"{before}->{sentiment}"] += 1
            apply(by_id[mid], sentiment)

    rerendered = 0
    for row in rows:
        messages = fmt.build_messages(row["meeting_notes"], row["signals"])
        if messages != row["messages"]:
            row["messages"] = messages
            rerendered += 1

    print(f"{sum(changed.values())} of {len(fixes)} fixes not yet applied: {dict(changed)}")
    print(f"{rerendered} rows re-rendered with the current prompt")
    print("Sentiment mix after fixes: "
          + str(collections.Counter(r["signals"]["customer_sentiment"] for r in rows)))
    if args.check or not rerendered:
        return 0
    with DATA_PATH.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"Wrote {DATA_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
