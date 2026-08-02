"""
ground_reasoning.py
====================
One-time migration that rewrites every Reason bullet in data/train.jsonl to
cite the actual input content, instead of just naming the field:

    Before: "Employees Count contributed 10 points."
    After:  "Employees Count of 474 indicates a mid-sized company, contributing 10 points."

Why this exists: the model was producing correctly-formatted, correctly-scored
output, but the Reason bullets never referenced anything from the specific
lead -- every "Hot" lead got interchangeable bullets that could apply to any
other Hot lead. This makes the reasoning traceable to the actual company's
numbers and stated requirement.

This does NOT change any scoring: Lead Score, Qualification, Priority,
Recommended Action, and each bullet's point value are read from the existing
(already-validated) output and carried over unchanged -- only the bullet
*text* is rewritten. This is safe to do deterministically because analysis of
the dataset showed every field's point tier already corresponds to a fixed
value range (Employees Count, Product Quantity, Deal Value) or an exact
string (Purchase Timeline has only 6 distinct values, each mapping 1:1 to a
points tier):

    Employees Count      0->1-50   5->53-200   10->208-982   15->1073-4994   20->5037-24894
    Product Quantity     0->1-10   5->11-50    10->51-100    15->103-494     20->506-1936
    Deal Value (INR)     0->25K-98K  5->114K-987K  10->1.0M-4.9M  15->5.3M-19.8M  20->20.6M-199M
    Purchase Timeline    More than 3 Months->0  Within 3 Months->5  Within 2 Months->10
                          Within 1 Month->15  Within 15 Days->20  Immediately->20

Run:
    python scripts/ground_reasoning.py
"""

import json
import re
import shutil
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DATA_PATH = PROJECT_ROOT / "data" / "train.jsonl"
BACKUP_PATH = PROJECT_ROOT / "data" / "train.jsonl.bak_pre_grounding"

BULLET_RE = re.compile(r"^• (.+?) contributed (\d+) points\.$", re.MULTILINE)

TIMELINE_DESCRIPTORS = {
    "Immediately": "signals maximum urgency",
    "Within 15 Days": "signals strong urgency",
    "Within 1 Month": "signals solid urgency",
    "Within 2 Months": "signals moderate urgency",
    "Within 3 Months": "signals mild urgency",
    "More than 3 Months": "signals no near-term urgency",
}


def employees_bullet(value: int, pts: int) -> str:
    if pts >= 20:
        desc = "a large enterprise"
    elif pts >= 15:
        desc = "a mid-to-large company"
    elif pts >= 10:
        desc = "a mid-sized company"
    elif pts >= 5:
        desc = "a small company"
    else:
        desc = "a very small company"
    return f"Employees Count of {value} indicates {desc}, contributing {pts} points."


def quantity_bullet(value: int, pts: int) -> str:
    if pts >= 20:
        desc = "a large-scale order"
    elif pts >= 15:
        desc = "a sizable order"
    elif pts >= 10:
        desc = "a moderate order size"
    elif pts >= 5:
        desc = "a small order"
    else:
        desc = "a minimal order size"
    return f"Product Quantity of {value} units reflects {desc}, contributing {pts} points."


def deal_value_bullet(value_str: str, pts: int) -> str:
    if pts >= 20:
        desc = "the highest deal-value tier"
    elif pts >= 15:
        desc = "a high-value deal tier"
    elif pts >= 10:
        desc = "a mid-tier deal range"
    elif pts >= 5:
        desc = "a low-value deal tier"
    else:
        desc = "the lowest deal-value tier"
    return f"Deal Value of {value_str} places this in {desc}, contributing {pts} points."


def timeline_bullet(value_str: str, pts: int) -> str:
    desc = TIMELINE_DESCRIPTORS.get(value_str, "signals a defined purchase timeline")
    return f'Purchase Timeline of "{value_str}" {desc}, contributing {pts} points.'


def requirement_bullet(value_str: str, pts: int) -> str:
    if pts >= 20:
        desc = "confirmed buying intent"
    elif pts >= 15:
        desc = "strong, specific interest"
    elif pts >= 10:
        desc = "moderate, defined interest"
    elif pts >= 5:
        desc = "early-stage, exploratory interest"
    else:
        desc = "no concrete buying intent yet"
    return f'Customer Requirement — "{value_str}" — shows {desc}, contributing {pts} points.'


FIELD_BUILDERS = {
    "Employees Count": ("Employees Count", int, employees_bullet),
    "Product Quantity": ("Product Quantity", int, quantity_bullet),
    "Deal Value": ("Deal Value", str, deal_value_bullet),
    "Purchase Timeline": ("Purchase Timeline", str, timeline_bullet),
    "Customer Requirement": ("Customer Requirement", str, requirement_bullet),
}


def parse_input_fields(lead_input: str) -> dict:
    fields = {}
    for line in lead_input.splitlines():
        if ":" not in line:
            continue
        key, _, value = line.partition(":")
        fields[key.strip()] = value.strip()
    return fields


def ground_output(lead_input: str, output: str) -> str:
    input_fields = parse_input_fields(lead_input)
    bullets = BULLET_RE.findall(output)

    if len(bullets) != 5:
        raise ValueError(f"Expected 5 bullets, found {len(bullets)}:\n{output!r}")

    new_bullets = []
    for field_name, pts_str in bullets:
        if field_name not in FIELD_BUILDERS:
            raise ValueError(f"Unknown field in bullet: {field_name!r}")
        pts = int(pts_str)
        raw_value = input_fields.get(field_name)
        if raw_value is None:
            raise ValueError(f"Field {field_name!r} not found in input: {lead_input!r}")

        _, cast, builder = FIELD_BUILDERS[field_name]
        value = cast(raw_value) if cast is int else raw_value
        new_bullets.append(builder(value, pts))

    new_reason_block = "\n".join(f"• {b}" for b in new_bullets)
    return _replace_reason_section(output, new_reason_block)


def _replace_reason_section(output: str, new_reason_block: str) -> str:
    """Replace everything between 'Reason:\\n' and the next blank line
    (i.e. the 5 bullet lines) with the newly-grounded bullets."""
    pattern = re.compile(r"(Reason:\n)(?:• .+\n){5}", re.MULTILINE)
    new_output, n = pattern.subn(lambda m: m.group(1) + new_reason_block + "\n", output)
    if n != 1:
        raise ValueError(f"Expected to replace exactly 1 Reason block, replaced {n}:\n{output!r}")
    return new_output


def main():
    if not DATA_PATH.exists():
        raise FileNotFoundError(DATA_PATH)

    with open(DATA_PATH, "r", encoding="utf-8") as f:
        lines = f.readlines()

    shutil.copyfile(DATA_PATH, BACKUP_PATH)
    print(f"Backed up current dataset to: {BACKUP_PATH}")

    rewritten = []
    for line_no, line in enumerate(lines, start=1):
        obj = json.loads(line)
        try:
            obj["output"] = ground_output(obj["input"], obj["output"])
        except ValueError as e:
            raise ValueError(f"Line {line_no}: {e}") from e
        rewritten.append(json.dumps(obj, ensure_ascii=False))

    with open(DATA_PATH, "w", encoding="utf-8") as f:
        for line in rewritten:
            f.write(line + "\n")

    print(f"Rewrote Reason bullets for {len(rewritten)} records in: {DATA_PATH}")


if __name__ == "__main__":
    main()
