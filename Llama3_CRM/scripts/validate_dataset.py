import json
import re
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DATA_PATH = PROJECT_ROOT / "data" / "train.jsonl"

REQUIRED_FIELDS = [
    "instruction",
    "input",
    "output",
]

VALID_QUALIFICATIONS = {
    "Hot",
    "Warm",
    "Cold",
}

VALID_PRIORITIES = {
    "High",
    "Medium",
    "Low",
}

LEGACY_LABELS = {"Qualified", "Critical"}


def load_dataset():

    if not DATA_PATH.exists():
        raise FileNotFoundError(DATA_PATH)

    records = []

    with open(DATA_PATH, "r", encoding="utf-8") as f:

        for line_no, line in enumerate(f, start=1):

            try:
                obj = json.loads(line)
                obj["_line"] = line_no
                records.append(obj)

            except Exception:

                print(f"❌ Invalid JSON at line {line_no}")

    return records


def validate_required_fields(records):

    valid = True

    for row in records:

        for field in REQUIRED_FIELDS:

            if field not in row:

                print(
                    f"❌ Line {row['_line']} missing field '{field}'"
                )

                valid = False

    return valid



def validate_score(records):

    valid = True

    pattern = r"Lead Score:\s*(\d+)"

    for row in records:

        output = row["output"]

        match = re.search(pattern, output)

        if not match:

            print(
                f"❌ Line {row['_line']} missing Lead Score"
            )

            valid = False

            continue

        score = int(match.group(1))

        if score < 0 or score > 100:

            print(
                f"❌ Invalid Lead Score {score} at line {row['_line']}"
            )

            valid = False

    return valid



def validate_qualification(records):

    valid = True

    pattern = r"Qualification:\s*(.+)"

    for row in records:

        output = row["output"]

        match = re.search(pattern, output)

        if not match:

            print(
                f"❌ Missing Qualification at line {row['_line']}"
            )

            valid = False

            continue

        value = match.group(1).strip()

        if value not in VALID_QUALIFICATIONS:

            print(
                f"❌ Invalid Qualification '{value}'"
            )

            valid = False

    return valid


def validate_priority(records):

    valid = True

    pattern = r"Priority:\s*(.+)"

    for row in records:

        output = row["output"]

        match = re.search(pattern, output)

        if not match:

            print(
                f"❌ Missing Priority"
            )

            valid = False

            continue

        value = match.group(1).strip()

        if value not in VALID_PRIORITIES:

            print(
                f"❌ Invalid Priority '{value}'"
            )

            valid = False

    return valid



def validate_score_format(records):

    valid = True

    for row in records:

        output = row["output"]

        if not re.search(r"Lead Score:\s*\d+/100", output):

            print(
                f"❌ Line {row['_line']} Lead Score missing '/100' suffix"
            )

            valid = False

    return valid


def validate_reason_bullets(records):

    valid = True

    for row in records:

        output = row["output"]

        bullets = re.findall(r"^• (.+)$", output, re.MULTILINE)

        if len(bullets) != 5:

            print(
                f"❌ Line {row['_line']} has {len(bullets)} Reason bullets, expected 5"
            )

            valid = False

        if re.search(r"^-\s", output, re.MULTILINE):

            print(
                f"❌ Line {row['_line']} uses '-' bullets instead of '•'"
            )

            valid = False

    return valid


def validate_recommended_action(records):

    valid = True

    for row in records:

        output = row["output"]

        if "Recommended Action:" not in output:

            print(
                f"❌ Line {row['_line']} missing Recommended Action"
            )

            valid = False

    return valid


def validate_no_legacy_labels(records):

    valid = True

    for row in records:

        output = row["output"]

        for label in LEGACY_LABELS:

            if label in output:

                print(
                    f"❌ Line {row['_line']} contains legacy label '{label}'"
                )

                valid = False

    return valid


def validate_no_overall_explanation(records):

    valid = True

    for row in records:

        output = row["output"]

        if "Overall Explanation" in output:

            print(
                f"❌ Line {row['_line']} contains stray 'Overall Explanation' section"
            )

            valid = False

    return valid


def detect_duplicates(records):

    seen = set()

    duplicates = 0

    for row in records:

        key = row["input"]

        if key in seen:

            duplicates += 1

        seen.add(key)

    return duplicates


def main():

    records = load_dataset()

    print("=" * 50)
    print("CRM DATASET VALIDATION")
    print("=" * 50)

    print(f"Records : {len(records)}")

    validate_required_fields(records)

    validate_score(records)

    validate_qualification(records)

    validate_priority(records)

    validate_score_format(records)

    validate_reason_bullets(records)

    validate_recommended_action(records)

    validate_no_legacy_labels(records)

    validate_no_overall_explanation(records)

    duplicates = detect_duplicates(records)

    print(f"Duplicate Inputs : {duplicates}")

    print("=" * 50)
    print("Validation Completed")


if __name__ == "__main__":
    main()