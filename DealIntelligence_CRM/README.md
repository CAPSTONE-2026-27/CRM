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
   XgBoost/serve_api.py  →  deal score
```

The model **never predicts the deal score**. It produces the feature vector the
XGBoost regressor consumes. Separate concerns: the LLM reads unstructured text,
the regressor does the numeric prediction it was trained for.

Separate from [`../Llama3_CRM`](../Llama3_CRM), which fine-tunes the same base
model for a different task (initial lead scoring from a lead profile). Two
tasks, two adapters, one set of base weights.

## The contract is the hard part

Output goes straight into a scorer running `strict=True`, which has two failure
modes and **only one is loud**:

| | Behaviour |
|---|---|
| Ordinal column, bad value | `SchemaError` → HTTP 400. Loud, safe. |
| One-hot column, bad value | **Nothing.** `get_dummies` makes a column the bundle never saw, reindex drops it, every requirement/risk feature scores zero. The deal gets a confident score from evidence that vanished. |

So `scripts/deal_state_format.py` takes its vocabulary from the **saved bundle**,
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

## Isolation from Lead Scoring

The lead-scoring system in `../Llama3_CRM` is production and read-only. This
project shares exactly one thing with it: the base-model directory on disk,
which neither service writes to.

| | Lead Scoring | Deal Intelligence |
|---|---|---|
| Project | `../Llama3_CRM` | `DealIntelligence_CRM` |
| Port | 8001 | **8002** |
| Adapter | `lead_management_llama3_lora` | `deal_state_llama3_lora` |
| Dataset | its own `train.jsonl` | its own `train.jsonl` |
| Prompt | `prompt_format.py` | `deal_state_format.py` |
| API | OpenAI chat-completions | `POST /v1/deal-state` |
| Process | separate | separate |

Enforced by `tests/test_serve.py::TestIsolationFromLeadScoring`, which asserts
no script here imports from that project and none writes to the shared weights.

Port 8000 is the XGBoost deal scorer, so nothing here may use it.

## Layout

```
scripts/deal_state_format.py   vocabulary, prompt, coerce_state(), version
scripts/generate_dataset.py    synthetic journey generator
scripts/train.py               QLoRA fine-tune -> outputs/deal_state_llama3_lora
scripts/serve.py               inference API on :8002
scripts/check_lengths.py       truncation guard
tests/                         contract, dataset, API
data/train.jsonl               generated (not committed)
```

## Usage

```bash
# 1. Generate, validating every target against the real scorer
python scripts/generate_dataset.py --rows 800 --validate

# 2. Confirm nothing truncates
python scripts/check_lengths.py

# 3. Train (needs the GPU free — stop the lead-scoring server first)
python scripts/train.py

# 4. Serve
python scripts/serve.py          # :8002

# Tests (live-bundle tests skip cleanly without xgboost installed)
python -m pytest tests/ -v
```

## API

```
GET  /health        readiness, version, whether the adapter is loaded
GET  /v1/schema     the emitted contract — field order, vocabulary, defaults
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
 "adapter":        true,         // false = base model, NOT production output
 "model_version":  "1.0.0",
 "latency_ms":     4210}
```

**`repairs` is not decoration.** Every reply passes through `coerce_state()`,
which snaps values onto the trained vocabulary and imputes anything missing —
that guarantee is the point of the service, because an unrecognised one-hot
value does not raise at scoring time, it silently scores as zero. A state that
needed six repairs and one that needed none produce equally confident deal
scores, and this list is the only thing that distinguishes them. **Log it.**

**`adapter: false`** means the service is running the base model because no
trained adapter was found. It still answers, so the API and coercion layer can
be exercised before training finishes, but the output is not fit for scoring.

Two degradation paths worth knowing:

- Model unreachable → `503`. Never a fabricated state; a caller must be able to
  tell "service down" from "the deal genuinely looks like this".
- Reply contains no JSON → the previous state is carried forward with
  `total_meetings` incremented, and `repairs` says so. Losing an opportunity's
  entire state because one generation was malformed is worse than a state that
  did not move.

## Versioning

`CONTRACT_VERSION` in `deal_state_format.py` is bumped whenever the emitted
contract changes shape — a field added or removed, a value added or retired, a
numeric range moved. It is published by `/v1/schema` and returned on every
response, so a consumer can pin against it and a silent vocabulary change cannot
pass unnoticed.

`VERIFIED_AGAINST_BUNDLE` records which deal-score bundle the vocabulary was
checked against. **If the deal scorer is retrained, re-run
`tests/test_contract.py::TestLiveBundle` before assuming the vocabulary still
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

The base weights live in `../Llama3_CRM/models/Llama-3.1-8B-Instruct` and are
shared; this project trains its own adapter into `outputs/`.

`requirements.txt` notes one unresolved issue: `XgBoost/requirements.txt` pins
`xgboost==3.3.0` and the bundle's provenance records that version, but **no such
release exists on PyPI** (3.2.0 is the highest). The XGBoost serving environment
as written is not reproducible. 3.2.0 loads the bundle and validates schemas
correctly, but emits a version-mismatch warning and should not be trusted for
comparing predicted scores.
