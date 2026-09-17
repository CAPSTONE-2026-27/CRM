# TechCRM

An AI-assisted CRM: lead capture and scoring, sales pipeline, accounts and
contacts, cases, marketing campaigns, workflow automation, and RPA bots — with
lead scoring and post-meeting analysis driven by an LLM.

## Repository layout

```
.
├── frontend/               React 18 + Vite + TypeScript single-page app
├── backend/                Spring Boot REST API (Java 21)
├── ai/                     Llama 3.1 server + three LoRA adapters
│   ├── server/             main.py — serves all three adapters from one base model
│   ├── adapters/           lead_scoring · lead_meeting · deal_state
│   ├── base-model/         Llama-3.1-8B-Instruct (not in git)
│   └── tests/  tools/      test suite · smoke and system tests
├── xgboost/                Deal-score regressor
└── docs/                   Reports and reference documents
```

| Directory | Stack | Runs on |
|---|---|---|
| [`frontend/`](frontend/) | React, Vite, TanStack Query | `http://localhost:5173` |
| [`backend/`](backend/) | Spring Boot 4, JPA, Flyway, Spring Security | `http://localhost:8080` |
| [`xgboost/`](xgboost/) | Python, XGBoost, FastAPI | `http://127.0.0.1:8000` |
| [`ai/`](ai/) | Python, PyTorch/PEFT, FastAPI | `http://127.0.0.1:8001` |

The API serves everything under `/api`, e.g. `http://localhost:8080/api/leads`.

The backend degrades rather than fails when a model service is down: a deal
keeps its previous score, and a meeting write-up is always saved even if nothing
downstream of it succeeds.

### Stateful deal analysis (opt-in)

The deal-state adapter is **off by default**. Without it, each meeting write-up
is read on its own, so a field an earlier meeting established but this one does
not repeat falls back to a neutral default — and two of those fields are one-hot
columns the scorer accepts silently rather than rejecting, so the cost shows up
as a wrong score rather than an error.

To turn it on:

```bash
export DEAL_STATE_BASE_URL=http://127.0.0.1:8001  # then restart the backend
```

`ai/server/main.py` already serves `POST /v1/deal-state` next to the other two
adapters, so there is no extra service to start.

With it set, the deal flow sends the previous state alongside the new notes and
carries unchanged fields forward. `total_meetings`, `lead_score` and
`engagement_score` are always recomputed by the backend and never taken from the
model — they are arithmetic, and the adapter's own provenance records 40% field
accuracy on `lead_score`.

All three adapters share one 4-bit copy of the base model, so turning this on
costs no extra VRAM. Leave `DEAL_STATE_BASE_URL` unset to keep the previous
behaviour. See [`ai/adapters/deal_state/README.md`](ai/adapters/deal_state/README.md)
for the contract it maintains.

## Getting started

Prerequisites: **JDK 21**, **Node 20+** (for the frontend toolchain), and
**PostgreSQL 14+**.

```bash
# 1. Database
createdb crm_spring          # Flyway creates the schema on first start

# 2. API
cd backend
cp src/main/resources/application-local.yml.example \
   src/main/resources/application-local.yml   # then fill in your values
./mvnw spring-boot:run                        # http://localhost:8080

# 3. Frontend (separate terminal)
cd frontend
npm install
npm run dev                                   # http://localhost:5173

# 4. Deal scorer (separate terminal; optional — deal scoring degrades without it)
cd xgboost
python -m uvicorn serve_api:app --port 8000   # needs xgboost/requirements.txt

# 5. LLM server (separate terminal; optional — AI features degrade without it)
cd ai
python server/main.py                         # :8001, needs ai/server/requirements.txt
```

The base model and adapter weights are not in git. Place them at
`ai/base-model/Llama-3.1-8B-Instruct/` and `ai/adapters/<name>/weights/`; the
paths can be overridden with the variables in [`ai/.env.example`](ai/.env.example).

Open http://localhost:5173 and sign up — the first account creates the
organization, becomes its admin, and registers the three built-in RPA bots.

Full database instructions are in [docs/DATABASE-SETUP.md](docs/DATABASE-SETUP.md).

## Configuration

Everything is environment-overridable; local developer values belong in
`backend/src/main/resources/application-local.yml`, which is gitignored.

| Variable | Purpose |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL connection |
| `JWT_ACCESS_SECRET` | Signing key for access tokens |
| `AI_BASE_URL`, `AI_MODEL_NAME`, `AI_API_KEY` | OpenAI-compatible model endpoint |
| `GOOGLE_CLIENT_ID` / `_SECRET` | Google sign-in |
| `MICROSOFT_CLIENT_ID` / `_SECRET`, `MICROSOFT_TENANT_ID` | Microsoft sign-in |
| `FRONTEND_URL` | Where OAuth returns the browser after sign-in |

Leaving the OAuth or AI values unset does not prevent startup — those features
degrade rather than fail.

## Architecture notes

- **Auth** — JWT access tokens plus httpOnly refresh cookies, with optional
  Google and Microsoft SSO. Per-user, per-screen permissions; admins bypass.
  Every request is scoped to the caller's organization.
- **AI** — any OpenAI-compatible chat-completions endpoint (`AI_BASE_URL`). It
  defaults to the fine-tuned Llama 3.1 served locally by
  [`ai/server/main.py`](ai/server/main.py) on `:8001`; a
  hosted API works unchanged by setting the three `AI_*` variables. An
  unreachable model degrades gracefully; it never blocks a user from saving
  their own work.
- **RPA bots** — Spring `@Async` for event- and manually-triggered runs,
  `@Scheduled` for the hourly follow-up sweep. No broker required; see
  `BotExecutionService` for the trade-offs that choice implies.
- **Schema** — owned by Flyway migrations in
  [`backend/src/main/resources/db/migration`](backend/src/main/resources/db/migration).

## Documentation

- [Database setup](docs/DATABASE-SETUP.md)
- [Attributions](docs/ATTRIBUTIONS.md)
