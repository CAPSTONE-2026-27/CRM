# Lead Meeting adapter

A QLoRA fine-tune of Llama 3.1 8B Instruct that reads a sales executive's
qualification-meeting notes and extracts five business signals.

```
meeting notes (free text)
        ↓
   this model
        ↓
{customer_sentiment, buying_intent, decision_maker_involvement,
 customer_urgency, product_interest_level}
        ↓
   LeadScoreFluctuationEngine.java  →  meeting score, priority, updated lead score
```

The model **never produces a score**. Keeping the arithmetic in Java means any
score can be recomputed from the stored signals, and the weights can be retuned
without retraining.

One of four adapters under [`ai/adapters/`](..), next to `lead_scoring`,
`deal_state` and `marketing`, all on one set of base weights.

## Serving

`ai/server/main.py` attaches this adapter from `weights/` and routes a
`POST /v1/chat/completions` request to it when the system prompt is the one
`LeadMeetingExtractionClient.java` sends (`is_meeting_extraction()`). Decoding
is greedy with repetition penalty 1.02, the setting `eval/evaluate.py` measures.
If the adapter is missing, the route falls back to the base model and logs a
warning.

| | |
|---|---|
| Server | `ai/server/main.py`, port 8001 |
| Adapter | `ai/adapters/lead_meeting/weights/` (`CRM_MEETING_ADAPTER_PATH`) |
| Prompt | `ai/adapters/lead_meeting/prompt_format.py` |
| API | `POST /v1/chat/completions` |

## Data quality

* **Sentiment labels were reviewed.** Labels are sampled first and the base
  model writes a note to match, and about 18% of notes did not convey the
  sentiment they were generated for -- mostly "Positive" notes whose only cue
  was "engaged" or "receptive". `data/label_fixes.json` records 90 corrections
  with a reason each; `data/fix_labels.py` applies them. The rule is the one in
  `eval/sentiment_review.json`, and it is now also a line of `SYSTEM_PROMPT`
  (mirrored in `LeadMeetingExtractionClient.java`).
* **Short notes.** `train.jsonl` is 150-250 words of polished prose; real notes
  are often three lines or a few bullets. `generate_dataset.py --short` writes
  25-90 word fragment/bullet notes to `data/short.jsonl`, which `train.py`
  reads too. Each file is split on its own, so the 50 reviewed held-out notes
  stay held out.
* **A hand-written test set.** `eval/realistic.jsonl`: 40 notes in the shapes
  reps type, labelled with the prompt's guidance.

## Layout

```
prompt_format.py               vocabulary, prompt, chat template, parsing, score weights
train.py                       QLoRA fine-tune -> weights/
data/generate_dataset.py       labels-first synthetic meeting notes (uses the base model); --short for bullet notes
data/train.jsonl               generated dataset (committed, 494 rows, sentiment-corrected)
data/short.jsonl               short bullet/fragment notes (optional)
data/label_fixes.json          sentiment corrections, with reasons
data/fix_labels.py             applies label_fixes.json and re-renders every row with the current prompt
eval/evaluate.py               per-field accuracy vs. majority-class floor -> eval/results_<set>.json
eval/sentiment_review.json     hand-reviewed sentiment for the held-out notes
eval/build_realistic.py        writes eval/realistic.jsonl (40 hand-written notes)
weights/                       trained adapter (gitignored)
logs/                          training logs (gitignored)
```

Tests live in `ai/tests/test_meeting_review.py`, `ai/tests/test_retrain_data.py`
and `ai/tests/test_server.py`.

## Usage

Run from `ai/`, with `requirements-train.txt` installed:

```bash
# 1. Generate (both load the base model; stop server/main.py first on a 16GB card)
python adapters/lead_meeting/data/generate_dataset.py --rows 500
python adapters/lead_meeting/data/generate_dataset.py --short --rows 250

# 2. Apply the sentiment corrections; also needed after any SYSTEM_PROMPT edit
python adapters/lead_meeting/data/fix_labels.py

# 3. Train — stops server/main.py, trains, restarts it (the two don't fit in 16GB together)
python tools/train_offline.py lead_meeting
# or, with the server already stopped:
python adapters/lead_meeting/train.py

# 4. Evaluate
python adapters/lead_meeting/eval/evaluate.py                    # held-out split
python adapters/lead_meeting/eval/evaluate.py --set realistic    # hand-written notes

# 5. Serve (all adapters)
python server/main.py            # :8001
```
