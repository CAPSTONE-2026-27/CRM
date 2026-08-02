"""
reformat_dataset.py
====================
One-time migration that rewrites every `output` in data/train.jsonl into one
canonical response format.

Why this exists: the dataset was built up over multiple generation passes
that disagreed with each other —

  - 100/500 rows used a legacy 4-tier label scheme ("Qualified" / "Critical")
    instead of the target 3-tier scheme ("Hot"/"Warm"/"Cold",
    "High"/"Medium"/"Low"). These are remapped (Qualified -> Hot,
    Critical -> High); Priority is then re-derived from Qualification for
    every row so the two fields can never disagree.
  - All 500 rows used "-" bullets and appended an "Overall Explanation"
    paragraph that isn't part of the target format, and none of them
    included a "Recommended Action" section even though the target format
    requires one.

This script parses the existing structured fields out of each output
(they're all present, just wrongly formatted) and re-emits them in the exact
target shape. Nothing here is model-generated — every field is either copied
from the existing output or derived by a fixed lookup table, so the
regenerated file is fully deterministic and reproducible.

Run:
    python scripts/reformat_dataset.py
"""

import json
import re
import shutil
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DATA_PATH = PROJECT_ROOT / "data" / "train.jsonl"
BACKUP_PATH = PROJECT_ROOT / "data" / "train.jsonl.bak_pre_reformat"

QUALIFICATION_REMAP = {
    "Qualified": "Hot",
    "Hot": "Hot",
    "Warm": "Warm",
    "Cold": "Cold",
}

PRIORITY_BY_QUALIFICATION = {
    "Hot": "High",
    "Warm": "Medium",
    "Cold": "Low",
}

RECOMMENDED_ACTION_BY_QUALIFICATION = {
    "Hot": "Assign immediately to a senior sales representative and initiate contact within 24 hours.",
    "Warm": "Add to the active follow-up queue and re-engage within 3-5 business days.",
    "Cold": "Add to the long-term nurture sequence and revisit in 30-60 days.",
}

SCORE_RE = re.compile(r"Lead Score:\s*(\d+)")
QUALIFICATION_RE = re.compile(r"Qualification:\s*(.+)")
BULLET_RE = re.compile(r"^- (.+)$", re.MULTILINE)


def reformat_output(output: str) -> str:
    score_match = SCORE_RE.search(output)
    qualification_match = QUALIFICATION_RE.search(output)
    bullets = BULLET_RE.findall(output)

    if not score_match or not qualification_match or len(bullets) != 5:
        raise ValueError(
            f"Unexpected output shape (score={bool(score_match)}, "
            f"qualification={bool(qualification_match)}, bullets={len(bullets)}):\n{output!r}"
        )

    score = int(score_match.group(1))
    raw_qualification = qualification_match.group(1).strip()
    qualification = QUALIFICATION_REMAP[raw_qualification]
    priority = PRIORITY_BY_QUALIFICATION[qualification]
    recommended_action = RECOMMENDED_ACTION_BY_QUALIFICATION[qualification]

    reason_lines = "\n".join(f"• {b}" for b in bullets)

    return (
        f"Lead Score: {score}/100\n\n"
        f"Qualification:\n{qualification}\n\n"
        f"Priority:\n{priority}\n\n"
        f"Reason:\n{reason_lines}\n\n"
        f"Recommended Action:\n{recommended_action}"
    )


def main():
    if not DATA_PATH.exists():
        raise FileNotFoundError(DATA_PATH)

    with open(DATA_PATH, "r", encoding="utf-8") as f:
        lines = f.readlines()

    shutil.copyfile(DATA_PATH, BACKUP_PATH)
    print(f"Backed up original dataset to: {BACKUP_PATH}")

    rewritten = []
    remap_counts = {"Qualified->Hot": 0, "Critical->High": 0}

    for line_no, line in enumerate(lines, start=1):
        obj = json.loads(line)
        old_output = obj["output"]

        if "Qualified" in old_output:
            remap_counts["Qualified->Hot"] += 1
        if "Critical" in old_output:
            remap_counts["Critical->High"] += 1

        try:
            obj["output"] = reformat_output(old_output)
        except ValueError as e:
            raise ValueError(f"Line {line_no}: {e}") from e

        rewritten.append(json.dumps(obj, ensure_ascii=False))

    with open(DATA_PATH, "w", encoding="utf-8") as f:
        for line in rewritten:
            f.write(line + "\n")

    print(f"Rewrote {len(rewritten)} records to: {DATA_PATH}")
    print(f"Legacy label remaps: {remap_counts}")


if __name__ == "__main__":
    main()
