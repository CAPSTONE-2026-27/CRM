# TechCRM documentation

A CRM with two AI models wired into the sales process: a **language model** that
reads free text into structured judgements, and an **XGBoost model** that turns
those judgements into a deal score.

---

## Read in this order

| # | Document | What it covers | Read it when |
|---|---|---|---|
| 1 | **[SYSTEM-OVERVIEW.md](SYSTEM-OVERVIEW.md)** | Architecture, the three processes, how to start them, configuration, authentication, multi-tenancy, migrations, the lifecycle of one request | **First. Always.** Everything else assumes it. |
| 2 | **[LEAD-AND-DEAL-FLOW.md](LEAD-AND-DEAL-FLOW.md)** | The sales workflow lead → qualified → assigned → opportunity → meetings → scored → reviewed → won → onboarded, and every API endpoint that drives it | You need to know *what the system does* |
| 3 | **[AI-INTEGRATION.md](AI-INTEGRATION.md)** | Every LLM and XGBoost call site, the prompts, how the two models connect, and what happens when either fails | You are working on anything AI-related |
| 4 | **[BACKEND-GUIDE.md](BACKEND-GUIDE.md)** | Every Java package and file | You are changing the backend |
| 5 | **[FRONTEND-GUIDE.md](FRONTEND-GUIDE.md)** | Every React file | You are changing the frontend |

Reference:

| Document | Covers |
|---|---|
| [DATABASE-SETUP.md](DATABASE-SETUP.md) | Getting PostgreSQL running |
| [BACKEND-MIGRATION.md](BACKEND-MIGRATION.md) | History of the Node → Spring Boot migration |
| [DESIGN-GUIDELINES.md](DESIGN-GUIDELINES.md) | Visual language |
| [ATTRIBUTIONS.md](ATTRIBUTIONS.md) | Third-party credits |

---

## Day one: get it running

Three processes. All must be up.

```bash
# 1. Deal-scoring model  → :8000
cd XgBoost && ./.venv/bin/python serve_api.py

# 2. Backend API         → :8080
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# 3. Frontend            → :5173
cd frontend && npm run dev
```

Then open **http://localhost:5173**.

Verify:

```bash
curl http://localhost:8080/actuator/health   # status UP, db UP
curl http://127.0.0.1:8000/health            # status UP, model_version
```

If the backend cannot find Java, prefix with
`JAVA_HOME=/opt/homebrew/opt/openjdk@21`.

---

## The 60-second summary

**Lead flow** — a lead is created and the LLM scores *and* qualifies it in one
call. Qualified leads get assigned to a sales executive. The executive records
how first contact went. If the customer agrees to meet, the lead converts into
an **opportunity**, keeping a permanent link back to the lead.

**Deal flow** — the executive holds the meeting and submits a structured 16-field
write-up. That triggers the pipeline:

```
Meeting write-up
  → LLM extracts 14 business parameters (value + confidence + explanation)
  → feature engineering maps them to the 17 inputs XGBoost was trained on
  → XGBoost predicts a deal score
  → interpretation adds win probability, risk level, factors, recommendation
  → the sales manager approves, rejects or overrides
```

Every submission creates a **new version of the whole chain**. Nothing is
overwritten, so a deal's score progression is readable meeting by meeting.

---

## Where things live

```
backend/     Spring Boot API          → BACKEND-GUIDE.md
frontend/    React SPA                → FRONTEND-GUIDE.md
XgBoost/     Deal-scoring model       → AI-INTEGRATION.md §2
ml/          Fine-tuning work (not wired in)
Llama3_CRM/  Lead-scoring experiments (not wired in)
docs/        You are here
```

---

## Two things that bite newcomers

**Secrets.** `backend/src/main/resources/application.yml` is **committed to
git**. `application-local.yml` is **gitignored**. Real credentials — database
password, OAuth client secrets, API keys — belong in the local file only.
Anything written literally into `application.yml` is published the moment the
repository is pushed, and must be treated as leaked and rotated.

**No type checking.** TypeScript is not a project dependency; Vite transpiles
with esbuild, which strips types without checking them. `npm run build` catches
syntax and import errors only — a type error reaches runtime.
