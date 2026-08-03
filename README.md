# TechCRM

An AI-assisted CRM: lead capture and scoring, sales pipeline, accounts and
contacts, cases, marketing campaigns, workflow automation, and RPA bots — with
lead scoring and post-meeting analysis driven by an LLM.

## Repository layout

```
.
├── frontend/     React 18 + Vite + TypeScript single-page app
├── backend/      Spring Boot REST API (Java 21)
├── XgBoost/      Deal-scoring model + its HTTP serving layer
├── Llama3_CRM/   Llama-3 fine-tuning for CRM lead scoring
└── docs/         Setup and reference documentation
```

| Directory | Stack | Runs on |
|---|---|---|
| [`frontend/`](frontend/) | React, Vite, TanStack Query | `http://localhost:5173` |
| [`backend/`](backend/) | Spring Boot 4, JPA, Flyway, Spring Security | `http://localhost:8080` |
| [`XgBoost/`](XgBoost/) | Python, XGBoost, FastAPI | `http://127.0.0.1:8000` |
| [`Llama3_CRM/`](Llama3_CRM/) | Python, PyTorch/PEFT | offline training |

The API serves everything under `/api`, e.g. `http://localhost:8080/api/leads`.

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

# 3. Deal-scoring model (separate terminal)
cd XgBoost
python3.13 -m venv .venv
./.venv/bin/pip install -r requirements.txt
./.venv/bin/uvicorn serve_api:app --port 8000  # http://127.0.0.1:8000

# 4. Frontend (separate terminal)
cd frontend
npm install
npm run dev                                   # http://localhost:5173
```

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
- **AI** — any OpenAI-compatible chat-completions endpoint (`AI_BASE_URL`), so
  the same code runs against a hosted API in development and a self-hosted
  vLLM/Ollama server serving the fine-tuned model from [`Llama3_CRM/`](Llama3_CRM/) in
  production. An unreachable model degrades gracefully; it never blocks a user
  from saving their own work.
- **Deal scoring** — an XGBoost regressor (R² 0.90, MAE 4.4) predicts a 0-100
  close-likelihood from 17 signals a rep records on the deal. It is served over
  HTTP from [`XgBoost/`](XgBoost/) rather than reimplemented in Java: the model
  is a Python pickle whose encoders are part of the bundle, and re-deriving its
  ordinal ordering in Java would mean two definitions of the feature contract.
  The API stays up if the model is down; deals are simply saved unscored.
- **RPA bots** — Spring `@Async` for event- and manually-triggered runs,
  `@Scheduled` for the hourly follow-up sweep. No broker required; see
  `BotExecutionService` for the trade-offs that choice implies.
- **Schema** — owned by Flyway migrations in
  [`backend/src/main/resources/db/migration`](backend/src/main/resources/db/migration).

## Documentation

- [Database setup](docs/DATABASE-SETUP.md)
- [Design guidelines](docs/DESIGN-GUIDELINES.md)
- [Backend migration history](docs/BACKEND-MIGRATION.md)
- [Attributions](docs/ATTRIBUTIONS.md)
