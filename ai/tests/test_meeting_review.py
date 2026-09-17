"""
eval/sentiment_review.json is only meaningful for the notes it was written about.

It holds hand-reviewed customer_sentiment for the lead_meeting held-out split.
If the dataset is regenerated or the split changes, the review silently starts
scoring different notes against readings made of other text, so these tests
fail instead.

Run:
    python -m pytest tests/ -v
"""

import json
import sys
from pathlib import Path

import pytest

AI_ROOT = Path(__file__).resolve().parent.parent
MEETING_DIR = AI_ROOT / "adapters" / "lead_meeting"
# ai/ on the path and a package import: every adapter has a module named
# prompt_format, and a bare import would get whichever another test loaded first.
sys.path.insert(0, str(AI_ROOT))

from adapters.lead_meeting import prompt_format as meeting_fmt  # noqa: E402

REVIEW = json.loads((MEETING_DIR / "eval" / "sentiment_review.json").read_text(encoding="utf-8"))
DATASET = {
    row["meeting_id"]: row
    for row in map(json.loads, (MEETING_DIR / "data" / "meeting_train.jsonl").open(encoding="utf-8"))
}


def test_reviewed_values_are_in_vocabulary():
    allowed = set(meeting_fmt.ALLOWED_VALUES["customer_sentiment"])
    assert {entry["customer_sentiment"] for entry in REVIEW["labels"].values()} <= allowed


def test_reviewed_and_ambiguous_do_not_overlap():
    assert not set(REVIEW["labels"]) & set(REVIEW["ambiguous"])


def test_recorded_dataset_labels_still_match_the_dataset():
    # The review stores the label each note had when it was read. A mismatch
    # means the dataset changed underneath it.
    for meeting_id, entry in REVIEW["labels"].items():
        assert DATASET[meeting_id]["signals"]["customer_sentiment"] == entry["dataset_label"], meeting_id


def _train_constant(name):
    """A module-level literal from lead_meeting/train.py, read without importing it.

    Importing it would do a bare `import prompt_format`, which resolves to
    whichever adapter's module another test loaded first.
    """
    import ast

    tree = ast.parse((MEETING_DIR / "train.py").read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(getattr(t, "id", None) == name for t in node.targets):
            return ast.literal_eval(node.value)
    raise AssertionError(f"{name} not found in train.py")


def _is_valid(row):
    """Mirror of lead_meeting/train.py _is_valid, over the package-imported vocabulary."""
    messages = row.get("messages")
    if not messages or len(messages) != 3 or messages[-1].get("role") != "assistant":
        return False
    try:
        signals = json.loads(messages[-1]["content"])
    except (json.JSONDecodeError, TypeError):
        return False
    if not isinstance(signals, dict) or len(signals) != len(meeting_fmt.FIELD_ORDER):
        return False
    return all(signals.get(f) in meeting_fmt.ALLOWED_VALUES[f] for f in meeting_fmt.FIELD_ORDER)


def test_review_covers_exactly_the_held_out_split():
    datasets = pytest.importorskip("datasets")

    dataset = datasets.load_dataset(
        "json", data_files=str(MEETING_DIR / "data" / "meeting_train.jsonl"), split="train"
    ).filter(_is_valid)
    split = dataset.train_test_split(
        test_size=_train_constant("EVAL_FRACTION"), seed=_train_constant("SEED"), shuffle=True)
    held_out = set(split["test"]["meeting_id"])

    reviewed = set(REVIEW["labels"]) | set(REVIEW["ambiguous"])
    assert len(held_out) == 50
    # Every reviewed note is held out — never a training row — and every held-out
    # note was either reviewed or judged ambiguous.
    assert reviewed == held_out
