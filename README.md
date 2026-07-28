# TechCRM

An AI-assisted CRM: lead capture and scoring, sales pipeline, cases, marketing
campaigns, workflow automation, and RPA bots — with lead scoring and post-meeting
analysis driven by a fine-tuned LLM.

## Repository layout

```
.
├── frontend/        React 18 + Vite + TypeScript single-page app
├── backend/         Node/Express + Prisma REST API   ← the API the app runs against
├── backend-java/    Spring Boot API (original implementation, not currently running)
├── ml/              Llama-3 fine-tuning for CRM lead scoring
└── docs/            Setup and reference documentation
```

| Directory | Stack | Runs on |
|---|---|---|
| [`frontend/`](frontend/) | React, Vite, TanStack Query | `http://localhost:5173` |
| [`backend/`](backend/) | Express, Prisma, BullMQ, Passport | `http://localhost:4000` |
| [`backend-java/`](backend-java/) | Spring Boot, Flyway | not started by default |
| [`ml/`](ml/) | Python, PyTorch/PEFT | offline training |

> **Which backend is live?** `backend/` (Node) is what the frontend talks to and
> what the deployment uses. `backend-java/` is the earlier Spring Boot
> implementation of the same API, kept for reference — see its README before
> changing anything there.

## Getting started

Prerequisites: **Node 20+**, a **PostgreSQL 14+** database (local or hosted, e.g. Neon),
and **Redis** (for the RPA bot queue).

```bash
# 1. API
cd backend
npm install
cp .env.example .env         # fill in DATABASE_URL, JWT secrets, AI + OAuth keys
npx prisma migrate deploy    # creates the schema
npm run dev                  # http://localhost:4000

# 2. RPA worker (separate terminal — required for AI lead scoring)
cd backend
npm run worker

# 3. Frontend (separate terminal)
cd frontend
npm install
npm run dev                  # http://localhost:5173
```

Then open http://localhost:5173 and sign up — the first account creates the
organization and becomes its admin.

Full database instructions, including hosted and Docker options, are in
[docs/DATABASE-SETUP.md](docs/DATABASE-SETUP.md).

## Configuration

All backend configuration is environment-based. `backend/.env` is gitignored;
[`backend/.env.example`](backend/.env.example) documents every variable —
database URL, JWT secrets, the OpenAI-compatible AI endpoint, Redis, and the
Google / Microsoft OAuth credentials.

## Architecture notes

- **Auth** — JWT access tokens (15m) plus httpOnly refresh cookies, with optional
  Google and Microsoft SSO. Per-user, per-screen permissions; admins bypass.
- **AI** — any OpenAI-compatible endpoint (`AI_BASE_URL`), so the same code runs
  against a hosted API during development and a self-hosted vLLM/Ollama server
  serving the fine-tuned model from [`ml/`](ml/) in production.
- **RPA bots** — BullMQ jobs on Redis. Lead enrichment and case routing fire on
  record creation; follow-up sequencing runs hourly. Requires the worker process.

## Documentation

- [Database setup](docs/DATABASE-SETUP.md)
- [Design guidelines](docs/DESIGN-GUIDELINES.md)
- [Attributions](docs/ATTRIBUTIONS.md)
