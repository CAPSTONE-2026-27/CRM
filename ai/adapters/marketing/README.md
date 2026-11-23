# Marketing Automation adapter

A QLoRA fine-tune of Llama 3.1 8B Instruct for Module 11 (Marketing Automation).
It is the fourth adapter on the shared base model, next to `lead_scoring`,
`lead_meeting` and `deal_state`. One adapter covers both marketing tasks:

| Task | Input (built by Java) | Output |
|---|---|---|
| Lead evaluation (`LeadEvaluationAi`) | audience brief + rule results (PASS/FAIL/UNKNOWN) + one lead's business attributes | `{"lead_id","score","eligibility","confidence","positive_factors","negative_factors","reason"}` |
| Content generation (`ContentGenerationAi`) | campaign brief + style + channels with character limits (+ variant A for A/B) | `{"core":{…},"email":{…},"sms":{…},…}` |

The Java side is unchanged apart from a boot check. The validators, the single
retry, the rule guard and the rules/template fallbacks all stay in place: the
adapter makes those paths rarer, and they remain the safety net.

## How a request reaches it

```
ContentGenerationAi / LeadEvaluationAi
  → POST :8001/v1/chat/completions   (system prompt = resources/marketing/prompts/*.txt)
  → server/main.py _plan(): system text contains
        "you are the campaign audience analyst"  or  "you are the campaign copywriter"
  → adapter "marketing" (if weights/ exists; otherwise the base model, as before)
  → rendered with this adapter's chat template (no date preamble), backend's temperature
```

`PromptLibrary.java` refuses to boot if a prompt loses its marker, and
`prompt_format.py` reads the Java prompt files directly, so training, serving
and the backend always use the same bytes.

## Training data (`data/`)

All synthetic. Validate on real campaigns before trusting the scores.

* **`leads.jsonl`** (700 rows) comes from a deterministic teacher.
  `rules.py` ports the Java `RuleEngine`. A fixed fit table then scores
  industry, size, location, customer type, product, interest level, timeline,
  deal value, CRM score and qualification. Every target:
  * never marks a lead eligible when a required rule FAILs,
  * cites only attributes the lead has,
  * lowers confidence for each missing attribute and UNKNOWN rule.

  About 5% of rows carry the Java retry suffix.
* **`content.jsonl`** (~320 rows) is **validated distillation**. The base model
  writes copy for synthetic briefs through the running server. A row is kept
  only when every channel passes a port of `ContentOutputValidator`: grounded
  figures, no invented claims, within limits, bare JSON. Each brief gets up to
  3 samples. About 20% of briefs also produce an A/B variant-B row.
* **`train.jsonl`** is both files merged and shuffled. `train.py` reads it.

## Layout

```
prompt_format.py               reads the Java prompt files, chat template, task detection
rules.py                       port of the Java RuleEngine (teacher for lead rows)
train.py                       QLoRA fine-tune -> weights/ (+ weights/heldout.jsonl)
data/generate_dataset.py       leads / content / build steps
data/leads.jsonl               teacher-scored lead rows (700)
data/content.jsonl             validated distilled copy rows (~320)
data/train.jsonl               both merged and shuffled (what train.py reads)
eval/evaluate.py               held-out validity and accuracy through the running server
eval/results_<label>.json      last evaluation results per label
eval/build_realistic.py        writes eval/realistic.jsonl (hand-written campaigns, leads and briefs)
weights/                       trained adapter (gitignored)
logs/                          training logs (gitignored)
```

Tests live in `ai/tests/test_marketing_adapter.py`.

## Run

```bash
cd ai
python adapters/marketing/data/generate_dataset.py leads --rows 700
python adapters/marketing/data/generate_dataset.py content --rows 320   # server must be RUNNING (~2 h)
python adapters/marketing/data/generate_dataset.py build

# stop the model server: training and serving don't both fit on 16 GB
python adapters/marketing/train.py                                      # ~40-60 min on an RTX A4000

# start the server again; it attaches weights/ automatically
python adapters/marketing/eval/evaluate.py                              # adapter
CRM_MARKETING_ADAPTER_PATH=none python server/main.py                   # then, for a baseline:
python adapters/marketing/eval/evaluate.py --label base

# hand-written campaigns and briefs (no real campaign data yet)
python adapters/marketing/eval/evaluate.py --heldout adapters/marketing/eval/realistic.jsonl --label realistic
```

## Configuration

Same recipe as `deal_state`: 4-bit NF4, LoRA r=16 / alpha=32 / dropout 0.05 on
q/k/v/o/gate/up/down, paged AdamW 8-bit, lr 1e-4 cosine, effective batch 8,
completion-only loss, max length 2048. It differs in three ways:

* **3 epochs.** Half the targets are distilled, so more epochs would start
  memorising the briefs' wording.
* **Rows over 2048 tokens are dropped, not truncated.**
* **Distilled copy stays under 90% of every character limit**
  (`prompt_format.LENGTH_MARGIN`). Trained right up to the limit, the first
  adapter overshot it a few percent of the time (a 157-character preheader
  against 150), which the base model never did. `generate_dataset.py content`
  resamples a reply that is valid but too close to a limit, and `build` skips
  the 14 older rows that were.
* **The eval split is stratified by task.** The held-out rows are saved to
  `weights/heldout.jsonl` so `evaluate.py` scores exactly what training never saw.

## Metrics (`eval/results_<label>.json`)

| Metric | Meaning |
|---|---|
| lead `valid` | accepted by `LeadEvaluationValidator` on the first attempt |
| `eligibility_accuracy` | same eligible / not_eligible as the teacher |
| `rule_guard_would_fire` | said eligible although a required rule failed (should be 0) |
| `score_mae` | mean absolute score difference from the teacher |
| content `valid_first_attempt` | every channel passes `ContentOutputValidator` without a retry |
| `valid_within_margin` | valid, and every field under 90% of its limit |
| `channel_valid` | share of individual channels that pass |
| `lead_evaluation_feedback` | eligibility accuracy on rows built from real review decisions (invalid replies count as wrong) |

## Retraining from review decisions

The adapter keeps learning from the Marketing Head's decisions in the CRM. The
backend stores the exact prompt the model saw (`ai_input`, migration V33), and:

```
python tools/retrain.py              # from ai/; the weekly scheduled task runs tools/retrain_weekly.cmd
```

1. `data/collect_feedback.py` reads reviewed rows from the database
   (`CRM_FEEDBACK_DB_URL`). Overrides become corrected examples (the human's
   verdict, their note as the reason, the score moved into that verdict's band).
   Approvals that agree with the AI become confirmed examples, and approved
   AI or human-edited copy becomes a content example. About 20% of these go
   to `data/benchmark.jsonl` and are never trained on.
2. With fewer than 20 new rows it stops and leaves the server running.
3. Otherwise it stops the server and trains a candidate into `candidate/`.
4. It scores the candidate and the live adapter on the same benchmark, through a
   private server on port 8002.
5. It promotes the candidate only if it is better (see `decide()` in
   `tools/retrain.py`) and keeps the previous adapter in `weights.prev-<time>/`.
6. It restarts the CRM's server. A report goes to `logs/retrain-<time>.json`.

The CRM's AI is offline for about 3 hours while this runs. Rows reviewed before
V33 have no saved prompt and are not used.

## Known limits

* Lead scores reproduce the teacher's fit table. That is a sensible
  definition of fit, but it isn't learned from real conversions. Retrain
  the teacher's weights once real campaign outcomes exist.
* Distilled copy can only be as good as the base model's best valid sample.
  The adapter removes failures; it doesn't add creativity.
* English only in the training data. Other `style.language` values still
  work through the base model's multilingual ability, but they weren't tuned.
* The adapter was trained on the current prompt files. Editing a prompt
  means regenerating the dataset and retraining.
