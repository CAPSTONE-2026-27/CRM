# AI integration

Every place a model is called, what it is asked, and what happens when it fails.

There are **two models**, and they do different jobs:

| | Language model (LLM) | XGBoost model |
|---|---|---|
| **Job** | Read free text, produce structured judgements | Take structured inputs, predict a number |
| **Runs** | Local Python service on `:8001` | Local Python service on `:8000` |
| **Called from** | `ai/AiChatClient.java` | `deal/DealScoringClient.java` |
| **Call sites** | 4 | 2 |
| **Trained by us?** | **Partly** — see §1.2 | **Yes** — `XgBoost/` |

---

# Part 1 — The language model

## 1.1 The single integration point

Everything LLM-related goes through **`ai/AiChatClient.java`**. Nothing else in
the codebase opens an HTTP connection to a model.

It speaks the **OpenAI chat-completions protocol**, which is deliberate. A
hosted API (Groq) and a self-hosted server (vLLM, Ollama, llama.cpp) expose the
same interface, so pointing this at a different or fine-tuned model is a
configuration change, not a code change:

```yaml
ai:
  base-url:  http://localhost:8001/v1           # local server (default)
  model-name: crm-llama-3.1-8b-lora
  api-key:   ""                                 # blank for a self-hosted server
  request-timeout-ms: 180000
```

That is the entire integration surface. The migration off Groq changed these
four values and nothing else — no Java file was touched.

To go back to a hosted provider, set `AI_BASE_URL=https://api.groq.com/openai/v1`,
`AI_MODEL_NAME=llama-3.1-8b-instant`, `AI_API_KEY=gsk_...`.

### 1.2 What the local server actually serves

`Llama3_CRM/scripts/main.py` speaks the same OpenAI protocol and loads **one**
4-bit Llama 3.1 8B, with the LoRA adapter attached and toggled per request:

| Request | Adapter | Why |
|---|---|---|
| Lead scoring | **on** | The fine-tune was trained on exactly this task |
| Meeting analysis | off | Never trained on it — base instruct model handles it |
| Deal analysis | off | Never trained on it |
| Deal coach chat | off | Never trained on it |

Routing is by system prompt: `AiScoringClient`'s prompt opens with "You are a CRM
lead-scoring assistant", and `main.py` matches on that clause. **If that Java
string is ever reworded, `LEAD_SCORING_MARKER` in `main.py` must be reworded with
it** — otherwise lead scoring silently falls back to the base model. The test in
`Llama3_CRM/tests/test_bridge.py` pins both sides.

The adapter emits plain text (`Lead Score: 85/100 / Qualification: Hot / • five
bullets`), not JSON, and reads seven field names the CRM does not use. `main.py`
bridges both directions so `AiScoringClient` sees the exact JSON it always saw.

### 1.3 The five scoring factors

The fine-tune scores five factors at up to 20 points each, and defines the lead
score as their sum. Migration **V17** added the last two to the `leads` table, so
all five are now recordable:

| Factor | CRM field | Tiers (0 / 5 / 10 / 15 / 20 pts) |
|---|---|---|
| Employees Count | `employee_count` | ≤51 / ≤204 / ≤1027 / ≤5015 / above |
| Product Quantity | `product_quantity` | ≤10 / ≤50 / ≤101 / ≤500 / above |
| Deal Value | `estimated_deal_value` | ≤1.06L / ≤9.99L / ≤51.3L / ≤2.02Cr / above |
| Purchase Timeline | `purchase_timeline` | >3mo / 3mo / 2mo / 1mo / 15d or Immediately |
| Customer Requirement | `notes` | 20 fixed phrases, see `CUSTOMER_REQUIREMENT_TIERS` |

Scores are **deterministic** for known inputs: when the reply carries all five
bullets, `reconcile_output` recomputes every point value from the lead's actual
input and overrides whatever the model wrote. A model claiming 20 points for a
474-person company still scores it 10.

**`purchase_timeline` is an exact-string lookup.** The six accepted values are
declared in three places that must agree — `LeadRequest.PURCHASE_TIMELINES`, the
`leads_purchase_timeline_allowed` CHECK constraint, and `PURCHASE_TIMELINES` in
the frontend's `types.ts`. `"Within 1 month"` is not `"Within 1 Month"`: the
lowercase form scores 0 for urgency instead of failing.

**Leads missing factors are still scored**, on whatever was recorded, with the
remainder rescaled onto 0-100 (`CRM_NORMALISE_LEAD_SCORE=0` disables this) and
the gap named in `qualificationReasoning`. This is what pre-V17 rows do — their
scores are rankings, not values comparable to the training set. A lead with all
five factors needs no rescaling and is directly comparable.

Two methods:

| Method | Used by | Behaviour |
|---|---|---|
| `complete(messages)` | Scoring, analysis | Blocks, returns the reply text, or `null` on any failure |
| `stream(messages, onDelta)` | Deal coach | Reads SSE `data:` lines, pushes deltas to the callback, skips `[DONE]` |

**Timeouts matter.** A hung model call would otherwise hold an HTTP worker
thread for as long as the socket stays open.

## 1.2 Getting JSON out of a model — `ai/AiJson.java`

Three of the four call sites need structured JSON back. Instruction-tuned models
routinely wrap it in ``` fences or add a sentence of prose despite being told
not to. `AiJson.extractObject()` therefore takes **the first `{` to the last
`}`** rather than trusting the whole reply to parse.

`clampedScore()` forces any score into 0–100 and accepts both numbers and
numeric strings, because models return both.

## 1.3 The four LLM call sites

### ① Lead scoring and qualification
**`lead/AiScoringClient.java`** — called by `LeadService` on lead create/update
and by the async CSV-import scorer.

**Asked for:** a 0–100 score, a Hot/Warm/Cold label, a one-sentence reason,
plus a QUALIFIED/UNQUALIFIED verdict, a qualification probability and its
reasoning.

**Why one call, not two.** A lead scored 85 and simultaneously marked
unqualified is incoherent. Two independent calls have no way to prevent that.

**Prompt shape:**
```
Score the lead 0-100 on fit and intent signals, then decide whether the lead is
worth a sales executive's time... Respond with ONLY strict JSON:
{"score":<0-100>, "label":"Hot|Warm|Cold", "reason":"...",
 "qualificationStatus":"QUALIFIED|UNQUALIFIED",
 "qualificationProbability":<0-100>, "qualificationReasoning":"..."}
```

Only fields the rep actually filled in are sent — absent values are **omitted
rather than sent blank**, so the model is not invited to read "unknown" as
"none".

**Fallback:** if the model omits or garbles `qualificationStatus`, the score
decides it at a threshold of 45 — the same boundary as the "Warm" label, so the
fallback agrees with the temperature rather than contradicting it.

**On failure:** returns `null`. The lead saves unscored and is scored on a later
edit.

### ② Lead meeting analysis (Lead Output module)
**`meeting/MeetingAnalysisClient.java`** — called by `LeadMeetingService.analyze()`.

**Asked for:** a 2–4 sentence meeting summary, a re-scored 0–100, a label, and
2–5 short reasons for the movement.

**Why one call:** the score has to be justified by the same notes the summary is
drawn from. Splitting them risks the two disagreeing.

The previous score is given as the starting point, with instructions to move it
"only as far as the notes justify".

**On failure:** returns `null`. The UI hands the rep their own notes as the
starting summary and leaves the score for them to set by hand.

### ③ Deal meeting analysis — the big one
**`dealflow/DealAnalysisClient.java`** — called by `DealFlowService` when a
meeting output is submitted.

**Asked for:** 14 business parameters, each with a **value**, a **confidence
(0–1)** and a **one-sentence explanation** citing the evidence.

The 14: `customer_sentiment`, `buying_intent`, `relationship_strength`,
`budget_status`, `decision_maker_involvement`, `customer_urgency`,
`main_objections`, `product_interest_level`, `meeting_outcome`,
`customer_requirements`, `risk_factors`, `competitor_mentions`,
`implementation_readiness`, `upsell_opportunity`.

**The prompt is generated, not hardcoded.** It is built from
`DealParameters.ORDERED` and `ALLOWED_VALUES`, so the exact accepted values for
every field are listed in the prompt and cannot drift from the model bundle.

**Two accepted reply shapes.** The documented object form, and a bare scalar for
models that ignore the nesting — rejecting the second would throw away a
perfectly good reading over formatting.

**Confidence normalisation:** accepts both `0.9` and `90`, since models produce
both regardless of what the prompt asks for. Anything above 1 is read as a
percentage.

**On failure:** returns `null`, and the pipeline falls back to ④ below.

### ④ Deal coach (the chat assistant)
**`ai/DealCoachController.java`** — `POST /api/copilot/chat`, streaming over SSE.

Streams because a full answer takes several seconds and watching it arrive beats
staring at a spinner. Runs on `CompletableFuture.runAsync` so the model call does
not hold a servlet thread — otherwise concurrent chats would be capped at the
thread pool size.

**Security:** only `user` and `assistant` roles are forwarded from the request.
Anything else would let a crafted payload inject a second system prompt.

The system prompt tells it that it has no live access to records, so when a
question depends on specific data it should say what it would look at rather
than inventing figures.

**Event contract** (`delta` / `error` / `done`) is what `streamCopilotChat` in
`apiClient.ts` parses.

## 1.4 The non-LLM fallback

**`dealflow/HeuristicMeetingAnalyzer.java`** — used when ③ fails.

Keyword matching, and no pretence otherwise. Every value it returns carries a
confidence of **0.25** and an explanation saying it came from a keyword match.
Analyses built this way are stored with status `DEGRADED`, so a weak score can
be told apart from a confident one after the fact.

**Why have it at all:** an executive who has just spent two minutes writing up a
meeting should not get nothing back because a third-party API was down. The
write-up is the part worth keeping.

Parameters it cannot reasonably guess are **left out entirely** rather than
invented — the feature engineering layer then defaults them and records that it
did.

---

# Part 2 — The XGBoost model

## 2.1 What it is

A regression model that predicts a **0–100 deal score**. Trained separately in
`XgBoost/`, R² ≈ 0.90, MAE ≈ 4.36.

| File | Purpose |
|---|---|
| `XgBoost/deal_score_pipeline.py` | Feature engineering, encoders, training, `priority_band()` |
| `XgBoost/deal_score_service.py` | `DealScorer` — loads the bundle, scores records |
| `XgBoost/serve_api.py` | The FastAPI HTTP layer |
| `XgBoost/models/` | The trained bundle (pickle + encoders + provenance) |
| `XgBoost/tests/` | Tests |

**Endpoints:**

| Endpoint | Returns |
|---|---|
| `GET /health` | Liveness, model version, training date |
| `GET /schema` | The accepted values for every input, **read from the bundle** |
| `POST /score` | `deal_score`, `win_probability`, `band`, `action`, `clipped`, `model_version` |

Run it with `python serve_api.py` — the `__main__` guard reads
`DEAL_SCORER_HOST` / `DEAL_SCORER_PORT`.

> `GET /schema` reads the accepted values from the bundle rather than a hardcoded
> list, so the form and the model cannot drift apart after a retrain. Worth
> checking against `DealParameters.java` and `dealScoring.ts` whenever the model
> is retrained.

## 2.2 Strict mode — the constraint everything else bends around

The scorer **rejects a category it was not trained on** with a 400, rather than
quietly substituting the median. A confidently wrong score is worse than an
error.

This single decision drives:

- Why `DealParameters.snap()` exists at all.
- Why every categorical in `DealScoringForm.tsx` is a `select`, never free text.
- Why the LLM prompt enumerates the accepted values.

## 2.3 The two call sites

Both go through **`deal/DealScoringClient.java`**.

### ① Manual scoring — `deal/DealService.java`
The deal wizard collects the 17 inputs by hand (`DealScoringForm.tsx`), and the
deal is scored on create and update.

### ② Automatic scoring — `dealflow/DealFlowService.java`
The 17 inputs come from the LLM extraction and the feature engineering layer
instead of a form. Uses `scoreFeatures(modelInputs)`.

**On failure either way:** returns `null`. The deal keeps its previous score
rather than being wiped — a stale score is more useful than none while the
service is down.

## 2.4 Win probability — read this before quoting it to anyone

`win_probability` is a **calibration of the deal score, not a separately trained
classifier.**

The bundle was fitted to predict a 0–100 score. **No win/loss label was ever part
of its training data**, so there is no learned probability to read off. Exposing
a monotonic squash of the score is the honest option: it reorders nothing and
adds no information the score did not already carry.

```python
# XgBoost/serve_api.py
_WIN_PROBABILITY_SLOPE = math.log(3) / 25
win_probability = 1 / (1 + exp(-slope * (deal_score - 50)))
```

The slope is set so **75 → 0.75**, the point where the `HIGH` band begins, so the
two agree instead of telling the sales team different stories about the same
deal.

A logistic rather than `score / 100` because the tails should not be taken
literally. A model that outputs 97 has not found a deal that closes 97 times in
100; it has found one that resembles the best deals it was trained on. The curve
compresses that to ~0.93 and floors 0 at ~0.10.

**To make it a real probability:** train a classifier on closed-won/closed-lost
outcomes. `win_probability()` in `serve_api.py` is the single place to replace.

---

# Part 3 — How the two models connect

The LLM reads meetings. XGBoost scores deals. Neither can do the other's job.
**`dealflow/FeatureEngineeringService.java`** is the bridge.

```
Meeting write-up (free text)
   │
   ▼  DealAnalysisClient — LLM
14 parameters, each with value + confidence + explanation
   │
   ▼  FeatureEngineeringService — snap, default, derive
   ├── modelInputs : 17 categorical labels  ──►  XGBoost
   └── features    : 0-1 numeric vector     ──►  stored for humans
   │
   ▼  DealPredictionService — interpret
deal score · win probability · risk · factors · recommendation
```

## 3.1 Snapping — `DealParameters.snap()`

Prompt constraints are a request, not a guarantee, and the scorer rejects unknown
labels. So every value is snapped onto the bundle's vocabulary:

1. Exact match
2. Case-insensitive match
3. Substring, either direction

For step 3, **the earliest match in the string wins**, because a model asked for
one value routinely returns several (`"API / Technical Integration;
Compliance-driven Requirements"`) and the first one named is the one it
considered most salient. Where two candidates start at the same position, the
longer wins, so `"very high"` resolves to `Very High` rather than `High`.

Anything that still does not match is replaced with a **neutral default** and
recorded in `imputedFields`. Every default is the neutral or conservative option,
so a missing reading pulls the score toward the middle rather than inventing a
favourable signal.

## 3.2 Two representations, both stored

| Stored as | What it is | Why kept |
|---|---|---|
| `features` | 0–1 numeric vector, higher always better | What a human audits; what a future model could train on |
| `modelInputs` | The categorical labels actually sent | What was scored — needed to reproduce a disputed number |

Keeping only the numbers would make a disputed score impossible to reproduce;
keeping only the labels would throw the engineering step away entirely.

## 3.3 The three derived inputs

Three of the 17 are **not** extracted from the write-up:

| Input | Where it comes from |
|---|---|
| `total_meetings` | The meeting's own version number |
| `lead_score` | Carried across from the originating lead |
| `engagement_score` | Blended from sentiment, intent, interest, decision-maker and outcome |

The first two are facts the CRM already knows — asking a language model to invent
them from a write-up would be strictly worse than reading them. `engagement_score`
is derived rather than asked for separately so it cannot contradict the parts it
is made of: "highly engaged" alongside negative sentiment and a cancelled meeting
is noise the model cannot reconcile.

## 3.4 Interpretation — `DealPredictionService.java`

The score comes from the model. **Everything here is interpretation**, kept
separate so a sales leader can change it without a retrain.

**Recommendation thresholds:**

| Score | Recommendation |
|---|---|
| ≥ 80 | Proceed with proposal |
| 60–79 | Schedule follow-up meeting |
| < 60 | Improve engagement, resolve objections, increase stakeholder involvement |

**Risk is not the inverse of the score.** A deal can score well and still be
risky. The score sets a baseline (≥70 LOW, ≥45 MEDIUM, else HIGH), which explicit
signals then **escalate — never de-escalate**:

- Budget not allocated
- A named competitor
- Three or more objections
- No decision maker involved
- Half or more of the parameters imputed

**Confidence is not the score.** It is the mean extraction confidence — how much
of the meeting the model could actually read. Imputed fields count as **zero**
rather than being skipped: averaging only the parameters that were found would
hide exactly the problem the number exists to expose.

**Factors** exclude imputed parameters from both lists. "No decision maker
involved" is a real finding; "we defaulted decision-maker involvement because the
write-up never mentioned it" is not, and presenting the second as the first sends
a manager chasing a problem that was never observed.

---

# Part 4 — Failure behaviour, in one table

| What breaks | Result |
|---|---|
| LLM down, lead scoring | Lead saves unscored; scored on a later edit |
| LLM down, lead meeting | Rep's own notes become the summary; score set by hand |
| LLM down, deal analysis | Keyword fallback; analysis marked `DEGRADED`; score still produced |
| LLM down, deal coach | SSE `error` event; chat shows a failure message |
| Model reply unparseable | Same as unreachable |
| XGBoost service down | Meeting, parameters and features all save; `prediction` is `null`; deal keeps its previous score |
| XGBoost returns 400 | Logged loudly — it means a value was sent that the model has never seen, which is a bug, not a transient fault |
| Both down | The CRM still works as a CRM |

The rule throughout: **AI is an enhancement that may be unavailable, never a
required dependency.** No AI failure may lose a user's work.

---

# Part 5 — Other model folders

| Folder | Status |
|---|---|
| `XgBoost/` | **In use.** The deal-scoring model, served on `:8000`. |
| `Llama3_CRM/` | **In use.** The fine-tuned LLM, served on `:8001` by `scripts/main.py`. See §1.2. |
| `ml/` | Earlier fine-tuning work for lead scoring. Not wired into the running system. |

`scripts/main.py` is the only server the CRM talks to.

`scripts/serve.py` is an older, narrower server kept for testing the adapter
directly: it exposes a lead-only `POST /score` with its own request schema, and
the CRM never calls it. **Starting it instead of `main.py` is a silent failure** —
it answers a shape the backend does not send, and its default port 8000 collides
with the XGBoost deal scorer, so every AI feature falls back with no error in
either log. If you run it, give it a different port.

To serve a different model, put it behind an OpenAI-compatible endpoint and
repoint `ai.base-url` / `ai.model-name` / `ai.api-key`. No application code
changes — that is the whole point of §1.1.
