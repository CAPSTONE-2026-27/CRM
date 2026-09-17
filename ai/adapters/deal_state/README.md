# CRM Deal Intelligence

A QLoRA fine-tune of Llama 3.1 8B Instruct that maintains structured CRM deal
state across a sequence of customer meetings.

```
previous CRM Deal State (JSON) + latest meeting notes (text)
        ↓
   this model
        ↓
updated CRM Deal State (JSON, 17 fields)
        ↓
   xgboost/serve_api.py  →  deal score
```

The model **never predicts the deal score**. It produces the feature vector the
XGBoost regressor consumes. Separate concerns: the LLM reads unstructured text,
the regressor does the numeric prediction it was trained for.

One of three adapters under [`ai/adapters/`](..), next to `lead_scoring` (initial
lead scoring from a lead profile) and `lead_meeting` (qualification-meeting
extraction). Three tasks, three adapters, one set of base weights.

## The contract is the hard part

Output goes straight into a scorer running `strict=True`, which has two failure
modes and **only one is loud**:

| | Behaviour |
|---|---|
| Ordinal column, bad value | `SchemaError` → HTTP 400. Loud, safe. |
| One-hot column, bad value | **Nothing.** `get_dummies` makes a column the bundle never saw, reindex drops it, every requirement/risk feature scores zero. The deal gets a confident score from evidence that vanished. |

So `prompt_format.py` takes its vocabulary from the **saved bundle**,
verified by round-tripping every value through `transform_for_inference`. Five
differences from a natural reading of the spec were found that way:

| Field | Natural reading | Actual contract |
|---|---|---|
| `relationship_strength` | Weak / Moderate / Strong / Very Strong | **numeric 0–10** — `serve_api` declares `float`, a string is a 422 |
| `buying_intent` | Low / Medium / High / **Very High** | **no Very High** — see below |
| `main_objections` | JSON list | **`"A; B"` string** |
| `customer_requirements` | list, free vocabulary | **single value**, fixed 8 |
| `risk_factors` | list, free vocabulary | **single value**, fixed 8 |

The `buying_intent` case is the one worth remembering: `ORDINAL_LEXICON` in
`deal_score_pipeline.py` *does* define `"very high": 4`, so reading the source
says it is accepted. It is not — the bundle intersects that lexicon with labels
actually present in the training CSV, which only ever held Low/Medium/High.
**The source is aspirational; the bundle is the authority.** Pinned by
`test_buying_intent_very_high_really_is_rejected`.

## Serving

There is no separate deal-state server. `ai/server/main.py` loads this adapter
from `weights/` alongside the other two, onto the same 4-bit base model, and
serves it on **:8001** — so it costs no extra VRAM.

| | |
|---|---|
| Server | `ai/server/main.py` |
| Port | 8001 (8000 is the XGBoost deal scorer) |
| Adapter | `ai/adapters/deal_state/weights/` |
| Prompt | `ai/adapters/deal_state/prompt_format.py` |
| API | `POST /v1/deal-state` |

The backend uses it only when `DEAL_STATE_BASE_URL` is set; point it at
`http://127.0.0.1:8001`.

## Layout

```
prompt_format.py               vocabulary, prompt, coerce_state(), version
train.py                       QLoRA fine-tune -> weights/
data/generate_dataset.py       synthetic journey generator
data/check_lengths.py          truncation guard
data/train.jsonl               generated dataset (committed, 800 rows)
eval/evaluate.py               held-out field accuracy, changed-field recall
eval/evaluate_end_to_end.py    what extraction errors cost in deal-score points
weights/                       trained adapter (gitignored)
logs/                          training logs (gitignored)
```

Tests live in `ai/tests/test_contract.py`.

## Usage

Run from `ai/`, with `requirements-train.txt` installed:

```bash
# 1. Generate, validating every target against the real scorer
python adapters/deal_state/data/generate_dataset.py --rows 800 --validate

# 2. Confirm nothing truncates
python adapters/deal_state/data/check_lengths.py

# 3. Train (needs the GPU free — stop server/main.py first)
python adapters/deal_state/train.py

# 4. Evaluate
python adapters/deal_state/eval/evaluate.py
python adapters/deal_state/eval/evaluate_end_to_end.py --limit 30

# 5. Serve (all three adapters)
python server/main.py            # :8001

# Tests (live-bundle tests skip cleanly without xgboost installed)
python -m pytest tests/test_contract.py -v
```

## API

```
POST /v1/deal-state
```

```jsonc
// request
{"previous_state": { /* 17 fields; omit for the first meeting */ },
 "meeting_notes": "The CFO confirmed the full budget is approved..."}

// response
{"state":          { /* 17 fields — feed this to the XGBoost scorer */ },
 "changed_fields": ["budget_status", "buying_intent"],
 "repairs":        [],           // see below
 "adapter":        true,
 "model_version":  "1.0.0",      // CONTRACT_VERSION
 "latency_ms":     4210}
```

`GET /health` on the same server reports `adapters.deal_state`: whether this
adapter was found and attached at startup.

**`repairs` is not decoration.** Every reply passes through `coerce_state()`,
which snaps values onto the trained vocabulary and imputes anything missing —
that guarantee is the point of the service, because an unrecognised one-hot
value does not raise at scoring time, it silently scores as zero. A state that
needed six repairs and one that needed none produce equally confident deal
scores, and this list is the only thing that distinguishes them. **Log it.**

Degradation paths worth knowing:

- Base model still loading, or this adapter not found in `weights/` → `503`.
  There is no base-model fallback: an untrained model does not reliably emit a
  state the scorer accepts, and a caller must be able to tell "service not
  ready" from "the deal genuinely looks like this".
- Reply contains no JSON → the previous state is carried forward with
  `total_meetings` incremented, and `repairs` says so. Losing an opportunity's
  entire state because one generation was malformed is worse than a state that
  did not move.

## Versioning

`CONTRACT_VERSION` in `prompt_format.py` is bumped whenever the emitted
contract changes shape — a field added or removed, a value added or retired, a
numeric range moved. It is returned as `model_version` on every response, so a
consumer can pin against it and a silent vocabulary change cannot pass
unnoticed.

`VERIFIED_AGAINST_BUNDLE` records which deal-score bundle the vocabulary was
checked against. **If the deal scorer is retrained, re-run
`ai/tests/test_contract.py::TestLiveBundle` before assuming the vocabulary still
holds** — the bundle, not the pipeline source, is the authority.

## How the dataset is built

Deals are simulated as **journeys**, not independent rows. Each journey picks a
trajectory (advancing, stalling, deteriorating, recovering, erratic) and walks a
state forward one meeting at a time. Each step decides which fields move, writes
notes stating that evidence and nothing else, and emits one row.

The generator is the ground truth — notes are derived from the transition, never
the reverse — so every target is exactly what the notes support.

Two properties the tests enforce, both learned the hard way:

**Every target must be reachable from the input.** An earlier version drifted
`lead_score`, `engagement_score` and `relationship_strength` randomly per
meeting. Well-formed, validated fine, impossible to learn: nothing in the notes
implies 49 → 51. That trains the model to emit plausible jitter, which is the
invented movement the prompt forbids. All three are now deterministic functions
of fields the notes justify.

**Most fields must stay put.** The skill being trained is carrying unchanged
state forward. If the average meeting moved most of the state there would be
nothing to carry. `test_most_fields_stay_put_in_a_given_meeting` asserts at
least 8 of 16 carry forward on average.

Journeys also start at varied maturity (early / developing / mature). Starting
every deal cold produced a set where two thirds of rows scored under 40 and
almost none above 70 — the model would learn that high scores barely exist.

## Environment

The base weights live in `ai/base-model/Llama-3.1-8B-Instruct` and are shared by
all three adapters; set `CRM_BASE_MODEL` to train against a copy elsewhere. This
adapter trains into `weights/` and logs into `logs/`.

`ai/requirements-train.txt` notes one unresolved issue: `xgboost/requirements.txt`
pins `xgboost==3.3.0` and the bundle's provenance records that version, but **no
such release exists on PyPI** (3.2.0 is the highest). The XGBoost serving
environment as written is not reproducible. 3.2.0 loads the bundle and validates
schemas correctly, but emits a version-mismatch warning and should not be trusted
for comparing predicted scores.
