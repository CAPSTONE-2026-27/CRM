# Migration — Groq to the local fine-tuned Llama 3.1

**Status:** complete. **Java files changed: 0.**

---

## 1. What the migration turned out to be

The premise going in was that Groq was wired into four places and each needed
replacing. It was not. The CRM has exactly one LLM integration point —
`ai/AiChatClient.java` — which speaks the OpenAI chat-completions protocol.
"Groq" existed only as a default URL string in `application.yml`.

So the migration is: **serve the local model behind the same protocol, and
change the URL.** No new client class, no DTOs, no changes to the four callers.
Adding a parallel `LocalLLMClient` alongside `AiChatClient` would have created a
second provider abstraction next to the one that already worked.

### Where the work actually was

The fine-tuned adapter cannot serve three of the four modules:

| | Trained on it? | Emits JSON? |
|---|---|---|
| Lead scoring | Yes — 500 examples | **No** — plain text |
| Meeting analysis | No | No |
| Deal analysis (14 params) | No | No |
| Deal coach chat | No | No |

All four Java clients call `AiJson.extractObject()` and return `null` when the
reply holds no JSON. Pointing them at the adapter unmodified would have left
every AI feature silently on its fallback path — no errors, no logs, just
permanently unscored leads and heuristic deal analysis.

The fix is in the serving layer: `Llama3_CRM/scripts/main.py` loads one 4-bit
Llama 3.1 8B with the LoRA attached, and **toggles the adapter per request**.

---

## 2. Files changed

| File | Change |
|---|---|
| `backend/src/main/resources/application.yml` | `ai.base-url` → `http://localhost:8001/v1`, `ai.model-name` → `crm-llama-3.1-8b-lora`, `request-timeout-ms` 30s → 180s |
| `backend/src/main/resources/application-local.yml.example` | Local-model block as the documented default, hosted as the commented fallback |
| `docs/AI-INTEGRATION.md` | New §1.2 describing adapter routing and the scoring caveat |
| `README.md` | Architecture note points at the local server |

**No `.java` file was modified.** Verified: `mvnw compile` passes unchanged.

## 3. Files added

| File | Purpose |
|---|---|
| `Llama3_CRM/scripts/main.py` | OpenAI-compatible server with adapter routing and the lead-scoring bridge |
| `Llama3_CRM/tests/test_bridge.py` | Field mapping and text→JSON conversion (31 tests) |
| `Llama3_CRM/tests/test_server.py` | Wire formats `AiChatClient` parses (21 tests) |
| `Llama3_CRM/requirements.txt` | Pinned serving dependencies |

## 4. Dependencies

- **Removed:** none. `pom.xml` never had an AI dependency — `AiChatClient` uses
  Spring's own `RestClient`.
- **Added (Python only):** `fastapi`, `uvicorn`, `pydantic` on top of the
  existing training stack. See `Llama3_CRM/requirements.txt`.

## 5. Configuration

```yaml
ai:
  base-url: ${AI_BASE_URL:http://localhost:8001/v1}
  model-name: ${AI_MODEL_NAME:crm-llama-3.1-8b-lora}
  api-key: ${AI_API_KEY:}              # blank — self-hosted needs no token
  request-timeout-ms: ${AI_REQUEST_TIMEOUT_MS:180000}
```

Server-side environment variables (all optional):

| Variable | Default | Purpose |
|---|---|---|
| `CRM_PORT` | `8001` | **Not 8000** — `XgBoost/serve_api.py` owns that |
| `CRM_MODEL_PATH` | `models/Llama-3.1-8B-Instruct` | Base weights |
| `CRM_ADAPTER_PATH` | `outputs/lead_management_llama3_lora` | LoRA adapter |
| `CRM_NORMALISE_LEAD_SCORE` | `1` | See §7 |
| `CRM_CHAT_TEMPERATURE` | `0.7` | Deal coach only; JSON tasks stay greedy |
| `CRM_MAX_NEW_TOKENS` | `1024` | Deal analysis needs ~900 |

---

## 6. Testing

```bash
# 1. Unit tests — no GPU, no weights needed
cd Llama3_CRM && python -m pytest tests/ -v          # 52 tests

# 2. Start the server (~15s to load on an RTX A4000)
python scripts/main.py
curl http://localhost:8001/health                    # {"status":"ok",...}

# 3. Backend
cd backend && ./mvnw compile

# 4. Per module, through the UI
#    Lead scoring     — create a lead, confirm score/label/qualification populate
#    Meeting analysis — log a meeting, confirm summary + re-score
#    Deal analysis    — submit a meeting write-up, confirm 14 parameters
#    Deal coach       — open the assistant, confirm the answer streams in
```

### Verified end-to-end against the live model

| Module | Adapter | Latency | Result |
|---|---|---|---|
| Lead scoring | on | 15.3s | Valid JSON, score 75 / Hot / QUALIFIED |
| Meeting analysis | off | 6.7s | Valid JSON, summary + score + 3 reasons |
| Deal analysis | off | 12.8s | Valid JSON, all requested parameters with confidences |
| Deal coach (SSE) | off | 11.6s | 153 chunks, terminated with `[DONE]` |

---

## 7. Known limitations

**~~Lead scores are not comparable to the training set's absolute values.~~**
**Resolved by migration V17** — the CRM now records all five scoring factors
(`product_quantity` and `purchase_timeline` were added). A fully-filled lead
reaches a genuine 100/100 and rescaling becomes an identity.

The rescaling still applies to leads with blank factors — including every row
created before V17 — and those scores remain rankings rather than values
comparable to the training set. `qualificationReasoning` names which factors
were missing. Set `CRM_NORMALISE_LEAD_SCORE=0` to return the raw total instead.

**`purchase_timeline` must match the trained strings exactly.** Six accepted
values, declared in `LeadRequest.PURCHASE_TIMELINES`, a DB CHECK constraint, and
the frontend's `PURCHASE_TIMELINES`. A mismatch scores 0 for urgency rather than
failing visibly.

**The model pads its five-bullet template with invented factors.** A live run
scored a lead on "the company location, Tokyo, Japan" when no location was
sent. Bullets that do not correspond to a supplied factor are dropped from both
the score and the reason text (`_supplied_bullets`). Worth re-checking if the
adapter is retrained.

**Routing depends on a Java string.** `main.py` identifies a lead-scoring call
by matching the opening clause of `AiScoringClient.SYSTEM_PROMPT`. Reword that
prompt without updating `LEAD_SCORING_MARKER` and lead scoring silently falls
back to the base model. `test_bridge.py::TestRouting` pins both sides.

**Three modules run the base model.** Meeting analysis, deal analysis and the
deal coach are prompted, not fine-tuned — the same arrangement as under Groq,
just self-hosted. Fine-tuning them means new datasets in the shape those prompts
expect.

---

## 8. Risks

| Risk | Mitigation |
|---|---|
| Server down or still loading | Returns 503. `AiChatClient` catches it, returns `null`, existing fallbacks run. Nothing user-facing breaks. |
| Port 8001 collides with something else | `CRM_PORT`. 8000 is already the XGBoost deal scorer. |
| Latency — 6-15s vs Groq's sub-second | `request-timeout-ms` raised to 180s. Scoring is `@Async`; the coach streams. |
| GPU OOM under concurrency | `INFERENCE_LOCK` serialises all generation. Throughput is one request at a time by design — `model.generate()` is not thread-safe under `device_map="auto"`. |
| Model quality regression vs Groq | Modules 2-4 run the same base architecture Groq served. Module 1 is the fine-tune, with the caveats in §7. |
| Single point of failure | Set the three `AI_*` env vars to fail back to a hosted provider without a redeploy. |

## 9. Rollback

Fully reversible by environment variable — no code revert, no restart of
anything but the backend:

```bash
export AI_BASE_URL=https://api.groq.com/openai/v1
export AI_MODEL_NAME=llama-3.1-8b-instant
export AI_API_KEY=gsk_...
export AI_REQUEST_TIMEOUT_MS=30000
```

To roll back permanently, revert the `ai:` block in `application.yml`. The
Python files added under `Llama3_CRM/` are inert when nothing calls them.
