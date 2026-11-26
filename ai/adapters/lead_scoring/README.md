# Lead Scoring adapter

A QLoRA fine-tune of Llama 3.1 8B Instruct that scores a new lead from its
profile at capture time.

```
lead profile (employees, product quantity, deal value, timeline, requirement)
        ↓
   this model
        ↓
Lead Score /100 · Qualification (Hot/Warm/Cold) · Priority · 5 reasons · action
```

One of four adapters under [`ai/adapters/`](..), next to `lead_meeting`,
`deal_state` and `marketing`, all on one set of base weights.

## Serving

`ai/server/main.py` attaches this adapter from `weights/` and routes a
`POST /v1/chat/completions` request to it when the system prompt is the one
`AiScoringClient.java` sends (`is_lead_scoring()`). The server rebuilds the
prompt with `prompt_format.py`, so training and serving share the same bytes,
and converts the text reply into the JSON the backend expects.

| | |
|---|---|
| Server | `ai/server/main.py`, port 8001 |
| Adapter | `ai/adapters/lead_scoring/weights/` (`CRM_ADAPTER_PATH`) |
| Prompt | `ai/adapters/lead_scoring/prompt_format.py` |
| API | `POST /v1/chat/completions` |

## What the model actually decides

`reconcile_output()` recomputes four of the five factors (employees, quantity,
deal value, timeline) from the input, plus the total and the band, after
generation. The only judgement left to the model is the **Customer Requirement**
tier, read from the sales executive's free-text note: production sends whatever
the rep typed, and only the 20 template phrases in `CUSTOMER_REQUIREMENT_TIERS`
can be tiered without the model.

The first dataset used nothing but those 20 phrases, so the model was never
tested on a note it had not memorised. `data/generate_dataset.py` now writes
rep-style notes per tier, with a disjoint set of phrasings reserved for the
eval split, and `eval/realistic.jsonl` holds 40 hand-written notes that reuse
none of the generator's wording.

| Tier | Meaning |
|---|---|
| 0 | no concrete need: general enquiry, brochure, curiosity |
| 5 | exploratory: comparing vendors, collecting quotes, early research |
| 10 | a defined, standard need: contacts, lead tracking, ticketing, dashboards |
| 15 | a specific, demanding need: ERP/SAP/Tally integration, AI scoring, workflow automation |
| 20 | a buying signal: budget approved, ready to sign, finalising the vendor |

## Layout

```
prompt_format.py               system prompt, score bands, point tiers, reconcile_output(), CHAT_TEMPLATE
train.py                       QLoRA fine-tune -> weights/ (completion-only loss)
data/generate_dataset.py       synthetic leads, labels from the tier rules, rep-style requirement notes
data/train.jsonl               generated dataset (committed, 900 rows, `split` column)
data/validate_dataset.py       format and label checks on data/train.jsonl
eval/evaluate.py               requirement-tier, qualification and score accuracy -> eval/results_<set>.json
eval/build_realistic.py        writes eval/realistic.jsonl (40 hand-written leads)
weights/                       trained adapter (gitignored)
logs/                          training logs (gitignored)
```

Tests live in `ai/tests/test_lead_scoring_rules.py`, `ai/tests/test_retrain_data.py`
and `ai/tests/test_bridge.py`.

## Training choices

* **Completion-only loss.** The first adapter computed loss over the whole
  sequence, system prompt and lead profile included, and needed 8 epochs to
  move the decision tokens; it ended at train_loss 0.022 against eval_loss
  0.155. Training now renders rows through `CHAT_TEMPLATE` (byte-identical to
  `build_llama3_prompt()`) with `assistant_only_loss`.
* **4 epochs** with early stopping (patience 2) and the best checkpoint kept.
* **Split by the generator's `split` column**, not at random.

## Usage

Run from `ai/`, with `requirements-train.txt` installed:

```bash
# 1. Generate and check the dataset
python adapters/lead_scoring/data/generate_dataset.py --rows 900
python adapters/lead_scoring/data/validate_dataset.py

# 2. Train — stops server/main.py, trains, restarts it (the two don't fit in 16GB together)
python tools/train_offline.py lead_scoring
# or, with the server already stopped:
python adapters/lead_scoring/train.py

# 3. Evaluate
python adapters/lead_scoring/eval/evaluate.py                    # held-out split
python adapters/lead_scoring/eval/evaluate.py --set realistic    # hand-written leads

# 4. Serve (all adapters)
python server/main.py            # :8001
```
