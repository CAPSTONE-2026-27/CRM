"""
retrain.py
==========

The marketing adapter's retraining loop: learn from the Marketing Head's review
decisions, and replace the live adapter only when the new one measurably does
better.

    python tools/retrain.py                 # what the weekly scheduled task runs
    python tools/retrain.py --force         # retrain even with few new examples
    python tools/retrain.py --skip-collect  # reuse the last collected feedback

Steps
-----
1. collect   adapters/marketing/data/collect_feedback.py reads reviewed rows
             from the CRM database (CRM_FEEDBACK_DB_URL) into feedback.jsonl and
             benchmark.jsonl.
2. gate      fewer than --min-new feedback rows not yet trained on -> stop here,
             without touching the running server.
3. train     the model server is stopped (the two don't fit in 16GB together)
             and train.py writes a CANDIDATE to adapters/marketing/candidate/.
             The live adapter in weights/ is not touched.
4. score     the candidate, then the live adapter, are each served by a
             temporary server on 127.0.0.1:8002 (unreachable by the CRM) and
             scored by eval/evaluate.py on the same benchmark.jsonl. The live
             adapter's score is cached until the benchmark or the adapter changes.
5. decide    decide() below. Promotion moves weights/ to weights.prev-<time>/
             and the candidate into weights/; the last two backups are kept.
6. restart   the CRM's model server comes back on port 8001 in every case,
             including failures, on whichever adapter is now in weights/.

A report of each run goes to adapters/marketing/logs/retrain-<time>.json.
The CRM's AI is offline from step 3 to step 6, about 3 hours.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import traceback
from pathlib import Path

import psutil

sys.path.insert(0, str(Path(__file__).resolve().parent))
from train_offline import find_server, is_model_server, start_server, stop_server  # noqa: E402

AI_ROOT = Path(__file__).resolve().parent.parent
MARKETING = AI_ROOT / "adapters" / "marketing"
WEIGHTS = MARKETING / "weights"
CANDIDATE = MARKETING / "candidate"
FEEDBACK = MARKETING / "data" / "feedback.jsonl"
BENCHMARK = MARKETING / "data" / "benchmark.jsonl"
LOG_DIR = MARKETING / "logs"
STATE = LOG_DIR / "retrain_state.json"
EVAL_SERVER_LOG = AI_ROOT / "logs" / "retrain_eval_server.log"

EVAL_PORT = 8002
MIN_NEW_DEFAULT = 20
KEEP_BACKUPS = 2

# How much worse the candidate may be on a guard metric before it is refused.
# Small allowances because the benchmark is ~100 rows: one reply is ~1%.
VALID_TOLERANCE = 0.01
RULE_GUARD_TOLERANCE = 0.01
CONTENT_TOLERANCE = 0.03
# Below this many real feedback rows in the benchmark, their accuracy is too
# noisy to decide on, and the overall eligibility accuracy is used instead.
MIN_FEEDBACK_FOR_PRIMARY = 10
MAE_TIE_BREAK = 0.5


def log(message: str) -> None:
    print(f"[retrain {time.strftime('%Y-%m-%d %H:%M:%S')}] {message}", flush=True)


# --------------------------------------------------------------------------
# DECISION
# --------------------------------------------------------------------------
def _num(value) -> float:
    return float(value) if isinstance(value, (int, float)) else 0.0


def decide(current: dict, candidate: dict) -> tuple[bool, list[str]]:
    """Promote the candidate? Returns (promote, reasons). Pure; tested."""
    cur_lead, cand_lead = current["lead_evaluation"], candidate["lead_evaluation"]
    cur_content, cand_content = current["content_generation"], candidate["content_generation"]

    # Guards first: a gain in accuracy must not be bought with replies the
    # backend rejects, or with eligible verdicts on leads that fail a hard rule.
    if _num(cand_lead["valid"]) < _num(cur_lead["valid"]) - VALID_TOLERANCE:
        return False, [f"lead replies valid fell {cur_lead['valid']} -> {cand_lead['valid']}"]
    if _num(cand_lead["rule_guard_would_fire"]) > _num(cur_lead["rule_guard_would_fire"]) + RULE_GUARD_TOLERANCE:
        return False, [f"eligible-despite-failed-rule rose {cur_lead['rule_guard_would_fire']} "
                       f"-> {cand_lead['rule_guard_would_fire']}"]
    if cand_content["n"] and (_num(cand_content["valid_first_attempt"])
                              < _num(cur_content["valid_first_attempt"]) - CONTENT_TOLERANCE):
        return False, [f"content valid on first attempt fell {cur_content['valid_first_attempt']} "
                       f"-> {cand_content['valid_first_attempt']}"]

    cur_real = current.get("lead_evaluation_feedback") or {}
    cand_real = candidate.get("lead_evaluation_feedback") or {}
    if (cand_real.get("n") or 0) >= MIN_FEEDBACK_FOR_PRIMARY:
        metric, before, after = ("eligibility accuracy on real reviews",
                                 cur_real.get("eligibility_accuracy"), cand_real.get("eligibility_accuracy"))
    else:
        metric, before, after = ("eligibility accuracy", cur_lead["eligibility_accuracy"],
                                 cand_lead["eligibility_accuracy"])

    if _num(after) > _num(before):
        return True, [f"{metric} improved {before} -> {after}"]
    if _num(after) == _num(before) and _num(cand_lead["score_mae"]) < _num(cur_lead["score_mae"]) - MAE_TIE_BREAK:
        return True, [f"{metric} unchanged at {after}; score error fell "
                      f"{cur_lead['score_mae']} -> {cand_lead['score_mae']}"]
    return False, [f"{metric} did not improve ({before} -> {after})"]


# --------------------------------------------------------------------------
# STATE
# --------------------------------------------------------------------------
def example_key(row: dict) -> str:
    text = row["messages"][1]["content"] + "\x00" + row["messages"][2]["content"]
    return hashlib.sha1(text.encode("utf-8")).hexdigest()


def read_feedback_keys() -> set[str]:
    if not FEEDBACK.exists():
        return set()
    return {example_key(json.loads(line)) for line in FEEDBACK.open(encoding="utf-8") if line.strip()}


def load_state() -> dict:
    try:
        return json.loads(STATE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def save_state(state: dict) -> None:
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(state, indent=2), encoding="utf-8")


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


# --------------------------------------------------------------------------
# SCORING
# --------------------------------------------------------------------------
def score_adapter(adapter_dir: Path, label: str, results_path: Path) -> dict:
    """Serve adapter_dir on a private port and score it on the benchmark."""
    env = dict(os.environ, CRM_PORT=str(EVAL_PORT), CRM_HOST="127.0.0.1",
               CRM_MARKETING_ADAPTER_PATH=str(adapter_dir))
    launch = {"cmdline": [sys.executable, str(AI_ROOT / "server" / "main.py")],
              "cwd": str(AI_ROOT), "env": env}
    log(f"Scoring {label} ({adapter_dir.name}) on {BENCHMARK.name}")
    proc = start_server(launch, port=EVAL_PORT, log_path=EVAL_SERVER_LOG)
    if proc is None:
        raise RuntimeError(f"Evaluation server for {label} exited during startup; see {EVAL_SERVER_LOG}")
    try:
        subprocess.check_call(
            [sys.executable, str(MARKETING / "eval" / "evaluate.py"), "--server", f"http://127.0.0.1:{EVAL_PORT}",
             "--label", label, "--heldout", str(BENCHMARK), "--output", str(results_path)],
            cwd=str(AI_ROOT))
        return json.loads(results_path.read_text(encoding="utf-8"))
    finally:
        try:
            stop_server(psutil.Process(proc.pid))
        except psutil.NoSuchProcess:
            pass


def current_results(state: dict, stamp: str) -> dict:
    """The live adapter's benchmark score, cached until either side changes."""
    key = file_sha256(BENCHMARK) + ":" + file_sha256(WEIGHTS / "adapter_model.safetensors")
    cached = state.get("current_eval") or {}
    if cached.get("key") == key:
        log("Live adapter's score is cached for this benchmark.")
        return cached["results"]
    results = score_adapter(WEIGHTS, "current", LOG_DIR / f"retrain-{stamp}-current.json")
    state["current_eval"] = {"key": key, "results": results}
    save_state(state)
    return results


# --------------------------------------------------------------------------
# PROMOTION
# --------------------------------------------------------------------------
def drop_checkpoints(adapter_dir: Path) -> None:
    """Trainer checkpoints only matter for --resume; each is several hundred MB."""
    for ckpt in adapter_dir.glob("checkpoint-*"):
        shutil.rmtree(ckpt, ignore_errors=True)


def promote(stamp: str) -> Path:
    backup = MARKETING / f"weights.prev-{stamp}"
    WEIGHTS.rename(backup)
    CANDIDATE.rename(WEIGHTS)
    backups = sorted(MARKETING.glob("weights.prev-*"))
    for old in backups[:-KEEP_BACKUPS]:
        shutil.rmtree(old, ignore_errors=True)
    return backup


# --------------------------------------------------------------------------
# MAIN
# --------------------------------------------------------------------------
def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--min-new", type=int, default=MIN_NEW_DEFAULT,
                        help=f"minimum new feedback rows before retraining (default {MIN_NEW_DEFAULT})")
    parser.add_argument("--force", action="store_true", help="retrain regardless of --min-new")
    parser.add_argument("--skip-collect", action="store_true", help="use the existing feedback.jsonl")
    parser.add_argument("--no-restart", action="store_true", help="leave the model server stopped afterwards")
    args = parser.parse_args()

    stamp = time.strftime("%Y%m%d-%H%M")
    report: dict = {"started": time.strftime("%Y-%m-%d %H:%M:%S"), "outcome": None}
    LOG_DIR.mkdir(parents=True, exist_ok=True)

    # 1. collect
    if not args.skip_collect:
        log("Collecting review decisions from the CRM database.")
        if subprocess.call([sys.executable, str(MARKETING / "data" / "collect_feedback.py")], cwd=str(AI_ROOT)) != 0:
            log("Collection failed; nothing was changed.")
            return 1

    # 2. gate
    state = load_state()
    keys = read_feedback_keys()
    new = len(keys - set(state.get("trained_on", [])))
    report["feedback_rows"], report["new_feedback_rows"] = len(keys), new
    if new < args.min_new and not args.force:
        log(f"{new} new feedback rows (need {args.min_new}); not retraining this time.")
        return 0
    if not BENCHMARK.exists():
        log(f"{BENCHMARK} is missing; run collect_feedback.py first.")
        return 1

    # 3-6
    server = find_server()
    launch = None
    if server is not None:
        if not is_model_server(server):
            log(f"Port in use by pid {server.pid}, which is not the model server; refusing to stop it.")
            return 2
        launch = stop_server(server)
    else:
        log("The model server was not running; it will be left stopped afterwards.")

    code = 1
    try:
        shutil.rmtree(CANDIDATE, ignore_errors=True)
        log(f"Training a candidate on {new} new feedback rows ({len(keys)} in total).")
        env = dict(os.environ, CRM_MARKETING_OUTPUT_DIR=str(CANDIDATE))
        started = time.time()
        subprocess.check_call([sys.executable, str(MARKETING / "train.py")], cwd=str(AI_ROOT), env=env)
        report["train_minutes"] = round((time.time() - started) / 60, 1)
        drop_checkpoints(CANDIDATE)
        state["trained_on"] = sorted(keys)
        save_state(state)

        candidate = score_adapter(CANDIDATE, "candidate", LOG_DIR / f"retrain-{stamp}-candidate.json")
        current = current_results(state, stamp)
        promote_it, reasons = decide(current, candidate)
        report.update(current=current, candidate=candidate, reasons=reasons)

        if promote_it:
            backup = promote(stamp)
            # The promoted adapter's score is the new live score.
            state["current_eval"] = {
                "key": file_sha256(BENCHMARK) + ":" + file_sha256(WEIGHTS / "adapter_model.safetensors"),
                "results": candidate}
            save_state(state)
            report.update(outcome="promoted", backup=str(backup))
            log(f"PROMOTED: {'; '.join(reasons)}. Previous adapter kept in {backup.name}.")
        else:
            report["outcome"] = "kept current"
            log(f"KEPT the current adapter: {'; '.join(reasons)}. Candidate left in {CANDIDATE.name}/ for inspection.")
        code = 0
    except Exception as exc:  # noqa: BLE001 - the server must come back regardless
        report.update(outcome="failed", error=f"{type(exc).__name__}: {exc}")
        log(f"FAILED: {exc}\n{traceback.format_exc()}")
    finally:
        if launch is not None and not args.no_restart:
            log("Restarting the CRM's model server.")
            start_server(launch)
        report["finished"] = time.strftime("%Y-%m-%d %H:%M:%S")
        (LOG_DIR / f"retrain-{stamp}.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return code


if __name__ == "__main__":
    sys.exit(main())
