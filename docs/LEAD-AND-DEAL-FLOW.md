# Lead and deal workflow

The path a customer takes from first contact to onboarding, and the AI pipeline
that scores them along the way.

```
Lead created
  -> scored + qualified (LLM)
  -> assigned to a sales executive
  -> contacted
  -> converted to an opportunity
       -> meeting scheduled
       -> meeting output recorded
            -> analysed (LLM)          14 business parameters + confidence
            -> feature engineering     numeric vector + model input labels
            -> scored (XGBoost)        deal score + win probability
       -> sales manager reviews the recommendation
       -> proposal -> negotiation -> closed won / lost
       -> onboarding opens automatically on Closed Won
```

Every meeting submission creates a **new version** of that whole chain. Nothing
is overwritten, so a deal's score progression stays readable meeting by meeting.

---

## Lead endpoints

All paths are under `/api`. Every endpoint is scoped to the caller's
organization; `SALES_REP` and `SUPPORT_AGENT` additionally only see leads
assigned to them.

### `POST /leads`

Creates a lead and scores it synchronously. The scoring model returns the
temperature (`aiScore`, `aiScoreLabel`) **and** the qualification verdict in one
call — separating them would allow a lead scored 85 and marked unqualified.

Response adds:

| Field | Meaning |
|---|---|
| `qualificationStatus` | `PENDING` \| `QUALIFIED` \| `UNQUALIFIED` |
| `qualificationProbability` | 0-100, confidence that the lead is worth pursuing. Not a copy of the score. |
| `qualificationReasoning` | Why the model reached that verdict |

A qualified lead with no explicit assignee is auto-assigned to the least-busy
active `SALES_REP`. Unqualified leads are not assigned at all — that is the
point of the qualification step.

### `PATCH /leads/{id}`

Partial update. Only fields present in the body change; `null` means "leave
alone". Does **not** re-score — editing a phone number should not move a lead's
temperature.

`assignedToId` is the one field where blank and absent differ: `""` clears the
assignment, absent leaves it.

### `POST /leads/{id}/assign`

```json
{ "assignedToId": "12" }
```

`ADMIN` and `MANAGER` only. Returns **409** for an unqualified lead. Records
`assignedAt` and sets `assignmentStatus` to `ASSIGNED`.

### `POST /leads/{id}/contact-status`

```json
{ "contactStatus": "MEETING_SCHEDULED", "contactNotes": "..." }
```

One of `NOT_CONTACTED`, `MEETING_SCHEDULED`, `NO_RESPONSE`, `INTERESTED`,
`NOT_INTERESTED`. Unknown values are rejected with **400**.

### `POST /leads/{id}/convert`

```json
{
  "meetingScheduledAt": "2026-08-12T10:00:00+05:30",
  "meetingMode": "ONLINE",
  "meetingParticipants": "..."
}
```

Body is optional. Creates the opportunity and returns:

```json
{
  "leadId": "10", "dealId": "31", "opportunityId": "OPP-000031",
  "accountId": "9", "accountCreated": true
}
```

Refuses with **409** when the lead is unqualified, already converted, or the
contact status is not `MEETING_SCHEDULED`/`INTERESTED`. The account is matched
by company name and created if it doesn't exist. The lead is not consumed — it
keeps `convertedDealId` so a deal can always be traced to its origin. Supplying
meeting details moves the new opportunity straight to `MEETING_SCHEDULED`.

### `GET /leads/export`

CSV of every lead matching the current filters (`q`, `status`, `assignedToId`,
`sourceChannel`, `industry`), using the same visibility rules as `GET /leads`.
Unpaged — an export of page 1 of 40 is not an export.

---

## Deal endpoints

### `PATCH /deals/{id}` · `POST /deals/{id}/close`

```json
{ "stage": "CLOSED_WON", "closingReason": "..." }
```

Stages, in workflow order:

`OPPORTUNITY_CREATED` → `MEETING_SCHEDULED` → `PROSPECTING` → `QUALIFICATION` →
`PROPOSAL` → `NEGOTIATION` → `CLOSED_WON` \| `CLOSED_LOST`

The first two were added ahead of the original stages rather than replacing
them: existing deals sit on `PROSPECTING`/`QUALIFICATION`, and renaming those
would relabel history to match a workflow they never ran through.

Closing sets `closedAt` and `closingReason`. Reopening clears both — a stale
"lost to competitor" on an active deal would poison every report using it.
`CLOSED_WON` opens a customer onboarding record.

### `POST /deals/{id}/schedule-meeting`

```json
{ "meetingScheduledAt": "...", "meetingMode": "ONLINE", "meetingParticipants": "..." }
```

Advances `OPPORTUNITY_CREATED` to `MEETING_SCHEDULED`. Deliberately does not
move a deal that is already further along — re-booking a meeting must not drag
a negotiation backwards.

---

## The analysis pipeline

### `POST /deals/{id}/meeting-outputs`

The core of the flow. Records a structured meeting write-up and runs the whole
chain in one request. Required: `meetingDate`, `meetingTime`, `meetingSummary`.
Optional: `meetingType`, `participants`, `customerRequirements`,
`keyDiscussionPoints`, `customerQuestions`, `competitorMentioned`, `objections`,
`budgetDiscussion`, `timeline`, `nextSteps`, `executiveRemarks`.

Synchronous, and takes a few seconds. The executive who wrote the meeting up is
waiting to see what the model made of it; a queue would trade that for a spinner
and a polling loop.

Returns **201** with the full chain:

```json
{
  "id": "4", "version": 2,
  "analysis":   { "status": "SUCCEEDED", "modelVersion": "...", "latencyMs": 1208 },
  "parameters": [ { "name": "customer_sentiment", "displayName": "Customer sentiment",
                    "value": "Positive", "confidence": 0.9, "explanation": "..." } ],
  "featureSet": { "features": {...}, "modelInputs": {...}, "imputedFields": [...] },
  "prediction": { "dealScore": 90.37, "winProbability": 85.5, "band": "HIGH",
                  "riskLevel": "LOW", "confidence": 0.76,
                  "recommendedAction": "Proceed with proposal",
                  "positiveFactors": [...], "negativeFactors": [...] }
}
```

**Failure behaviour.** Each stage is persisted before the next runs, so a
partial chain can be inspected rather than lost:

- **Analysis model unavailable** — parameters come from a keyword-based
  fallback, and `analysis.status` is `DEGRADED` with an explanation. The score
  is still produced, but from weaker evidence.
- **Scoring model unavailable** — the meeting, parameters and features are all
  saved and `prediction` is `null`. The deal keeps its previous score; a stale
  number beats no number while the service is down. The meeting is never
  reported as having produced a prediction it didn't generate.

### `GET /deals/{id}/workspace`

Everything the deal workspace renders in one response: the deal's flow state,
its latest prediction, every meeting with its own parameters/features/prediction
(newest first), and the manager review history.

### `POST /deals/{id}/review`

```json
{ "decision": "APPROVED", "overriddenAction": "...", "comments": "..." }
```

`ADMIN` and `MANAGER` only. `APPROVED` \| `REJECTED` \| `OVERRIDDEN`; an
override requires `overriddenAction`. **409** if the deal has no prediction yet.

The model's recommendation is frozen into the review from the prediction, not
taken from the request — otherwise a retrain would silently rewrite what the
manager actually signed off on. An override becomes the deal's active next
action; a rejection deliberately leaves the model's recommendation standing so
the disagreement stays visible.

### `GET` / `PATCH /deals/{id}/onboarding`

The onboarding record, opened automatically on `CLOSED_WON`. `GET` returns a
null body when the deal isn't won yet — the absence is the answer, not an error.
`PATCH { "status": "IN_PROGRESS" }` advances it
(`INITIATED` → `IN_PROGRESS` → `COMPLETED` \| `CANCELLED`).

### `GET /deal-flow/parameters`

The extraction vocabulary — parameter names, display names, accepted values and
objection tokens — served from the same constants the backend uses, so the UI
cannot drift from the model bundle.

---

## How the two models fit together

The language model reads meetings. The XGBoost model scores deals. Neither can
do the other's job, and the **feature engineering layer** is what connects them.

**Extraction (step 6).** The analysis model returns 14 parameters, each with a
value, a confidence and a one-line explanation. The prompt lists the exact
accepted values for every field.

**Snapping (step 7).** Prompt constraints are a request, not a guarantee, and
the XGBoost scorer runs in strict mode — it rejects a label it wasn't trained on
rather than quietly treating it as the median. So every value is snapped onto
the bundle's vocabulary: exact match, then case-insensitive, then substring in
either direction. Where a model returns several values for a single-select
field, the first one named wins, since that is the one it considered most
salient. Anything that still doesn't match is replaced with a neutral default
and recorded in `imputedFields`.

**Two representations.** `features` is a 0-1 numeric vector oriented so higher
is always better; `modelInputs` is the categorical labels actually sent to
XGBoost. Both are stored because they answer different questions — the numbers
are what a human audits, the labels are what was scored. Keeping only one would
make a disputed score impossible to reproduce.

**Three derived inputs.** `total_meetings`, `lead_score` and `engagement_score`
are not extracted from the write-up. The first two are facts the CRM already
knows; asking a language model to invent them would be strictly worse than
reading them. `engagement_score` is blended from the extracted signals, so it
cannot contradict the parts it is made of.

**Recommendation thresholds (step 10).**

| Deal score | Recommendation |
|---|---|
| ≥ 80 | Proceed with proposal |
| 60-79 | Schedule follow-up meeting |
| < 60 | Improve customer engagement, resolve objections, increase stakeholder involvement |

**Risk is not the inverse of the score.** A deal can score well and still be
risky. The score sets a baseline (`≥70` LOW, `≥45` MEDIUM, else HIGH), which is
then escalated — never de-escalated — by explicit signals: an unallocated
budget, a named competitor, three or more objections, no decision maker, or half
the parameters having been imputed.

**Confidence is not the score.** It is the mean extraction confidence: how much
of the meeting the model could actually read. A score of 85 built on four
defaulted signals deserves less trust than a score of 70 built on fourteen clear
ones, and this is the number that says so.

### Win probability, honestly

`win_probability` is a **calibration of the deal score, not a separately trained
classifier**. The XGBoost bundle was fitted to predict a 0-100 score; no
win/loss label was ever part of its training data, so there is no learned
probability to read off.

It is a logistic squash of the score, with the slope set so that 75 maps to
0.75 — the point where the `HIGH` band begins, so the two agree rather than
telling the sales team different stories. A logistic rather than `score / 100`
because the tails should not be taken literally: a model that outputs 97 has not
found a deal that closes 97 times in 100, it has found one that resembles the
best deals it was trained on. The curve compresses that to ~0.93 and floors 0 at
~0.10.

Training a win/loss model on closed-won and closed-lost outcomes is the only
thing that would make this a real probability. `win_probability()` in
`XgBoost/serve_api.py` is the single place to replace when that happens.

---

## Database

| Table | Holds |
|---|---|
| `deal_meeting_outputs` | The structured write-up. Versioned per deal, append-only. |
| `deal_analyses` | One per meeting output: status, model, latency, raw reply |
| `deal_extracted_parameters` | One row per parameter, with confidence and explanation |
| `deal_feature_sets` | Engineered numeric vector, model inputs, imputed fields |
| `deal_predictions` | Score, win probability, band, risk, factors, model version |
| `deal_manager_reviews` | Decision, frozen recommendation, override, comments |
| `customer_onboardings` | Opened on Closed Won, one per deal |

Every table carries `organization_id`, and links back via `deal_id`,
`opportunity_id` and `lead_id`. Each is a stage of one chain hanging off a
single meeting output, rather than columns on `deals`, because every stage is
versioned: a second meeting produces a second full chain and the first stays
queryable. A deal's "current" score is simply its newest prediction —
denormalised onto `deals` so the pipeline board can colour thousands of cards
without a subquery per row.

`deal_analyses.raw_response` keeps the model's reply verbatim. When an
extraction looks wrong, the only way to tell a bad prompt from a bad parse is to
read what actually came back, and by then the call is long gone.

Migrations: `V15__lead_qualification_and_deal_flow.sql`,
`V16__string_lists_as_arrays.sql`.
