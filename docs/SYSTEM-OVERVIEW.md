# System overview

Start here. This explains what the system is, what runs where, and how a single
request travels through it. The per-file guides assume you have read this.

---

## 1. What this is

A CRM with two AI models wired into the sales process:

- A **language model** reads free text — lead notes, meeting write-ups — and
  turns it into structured judgements.
- An **XGBoost regression model** takes those structured judgements and predicts
  how likely a deal is to close.

Everything else — accounts, contacts, cases, campaigns, workflows, RPA bots — is
conventional CRM around that core.

## 2. The three processes

The system is **three separate programs**. All three must be running.

| Process | Port | Language | Started with |
|---|---|---|---|
| Frontend | 5173 | TypeScript / React / Vite | `cd frontend && npm run dev` |
| Backend API | 8080 | Java 21 / Spring Boot 4.1 | `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local` |
| Deal scoring model | 8000 | Python 3.13 / FastAPI | `cd XgBoost && ./.venv/bin/python serve_api.py` |

Plus two things the system talks to but does not run:

| Dependency | What it is |
|---|---|
| **Neon** | Hosted PostgreSQL. The only persistent store. |
| **Groq** | Hosted LLM API (OpenAI-compatible). Swappable — see [AI-INTEGRATION.md](AI-INTEGRATION.md). |

```
   Browser
      │  http://localhost:5173
      ▼
 ┌─────────────┐   /api/*    ┌──────────────┐   JDBC    ┌──────────┐
 │  React SPA  │────────────►│ Spring Boot  │──────────►│   Neon   │
 │   (Vite)    │◄────────────│   :8080      │           │ Postgres │
 └─────────────┘   JSON/SSE  └──────┬───────┘           └──────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    ▼                               ▼
            ┌───────────────┐              ┌─────────────────┐
            │  Groq  (LLM)  │              │ XGBoost service │
            │ chat API      │              │  :8000 FastAPI  │
            └───────────────┘              └─────────────────┘
```

**Why the model is a separate process.** The XGBoost model is a Python pickle
whose encoders are part of the bundle. Reimplementing its ordinal ordering and
one-hot layout in Java would create two definitions of the feature contract and
a silent scoring drift the first time they disagreed. Serving it over HTTP keeps
one definition.

## 3. Starting the system

Order does not matter — the backend degrades gracefully if the model service is
down — but this order avoids warnings in the log:

```bash
# 1. Model service
cd XgBoost && ./.venv/bin/python serve_api.py

# 2. Backend
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# 3. Frontend
cd frontend && npm run dev
```

Health checks:

```bash
curl http://localhost:8080/actuator/health   # {"status":"UP", ... "db":{"status":"UP"}}
curl http://127.0.0.1:8000/health            # {"status":"UP","model_version":"1.0.0"}
curl -o /dev/null -w '%{http_code}\n' http://localhost:5173   # 200
```

**Java note.** The backend needs JDK 21. If `./mvnw` reports "Unable to locate a
Java Runtime", prefix the command with
`JAVA_HOME=/opt/homebrew/opt/openjdk@21`.

## 4. Configuration

Two files, and the difference between them matters.

| File | Committed to git? | Purpose |
|---|---|---|
| `backend/src/main/resources/application.yml` | **Yes** | Defaults and structure. Reads secrets from `${ENV_VAR}` placeholders. |
| `backend/src/main/resources/application-local.yml` | **No** — gitignored | Real local credentials. Overrides the above. |

`application.yml` imports the local file via `spring.config.import:
"optional:application-local.yml"`. The imported file wins, so anything set in
`application-local.yml` overrides the same key in `application.yml`.

> **Secrets rule:** real credentials belong in `application-local.yml` only.
> `application.yml` is committed, so any literal value written there is
> published to GitHub the moment the repository is pushed. If you ever put a
> client secret, database password or API key directly in `application.yml`,
> treat it as leaked and rotate it.

What lives in configuration:

| Key | Meaning |
|---|---|
| `spring.datasource.*` | Neon connection. Needs `currentSchema=public`; the Neon role's `search_path` is empty. |
| `spring.flyway.schemas` / `default-schema` | Also `public`, for the same reason — JDBC `currentSchema` alone does not survive Neon's connection pooler. |
| `spring.security.oauth2.client.*` | Google and Microsoft sign-in. Spring Boot 4 **refuses to start** if a declared registration has a blank client id. |
| `MICROSOFT_TENANT_ID` | Which Microsoft endpoint: a tenant GUID, or `common` / `organizations` / `consumers`. Must match the app registration's supported account types. |
| `ai.base-url` / `ai.model-name` / `ai.api-key` | The LLM. Any OpenAI-compatible endpoint. |
| `deal-scoring.base-url` | The XGBoost service. Defaults to `http://127.0.0.1:8000`. |
| `app.jwt.access-secret` | Signs access tokens. Override outside local dev. |
| `app.frontend-url` | Where OAuth redirects the browser back to after sign-in. |

## 5. Authentication

Two token types, deliberately different:

| Token | Form | Lifetime | Stored |
|---|---|---|---|
| **Access token** | JWT, signed | 30 minutes | Frontend memory only — never localStorage |
| **Refresh token** | Opaque random string | 7 days | httpOnly cookie + SHA-256 hash in the database |

The refresh token is not a JWT because nothing needs to be encoded in it — it is
only ever looked up. Storing only its hash means a database leak does not yield
usable tokens.

**Request flow:**

1. `POST /api/auth/login` → sets the httpOnly refresh cookie, returns an access token in the body.
2. Every API call sends `Authorization: Bearer <access token>`.
3. On a 401, `apiClient.ts` calls `/api/auth/refresh` once, retries the original request, and only then gives up.
4. A page reload has no access token in memory, so the app silently refreshes on mount.

**OAuth (Google / Microsoft)** is handled entirely server-side. The button
navigates the browser to `http://localhost:8080/oauth2/authorization/{google|azure}`
— note this is at the **server root**, not under `/api`. Spring completes the
handshake, `OAuth2SuccessHandler` sets the same refresh cookie a normal login
would, and redirects to `{frontend-url}/?signed_in=1`. That query parameter is
how the SPA knows a session now exists that it cannot see (the cookie is
httpOnly).

Microsoft's registration id is **`azure`**, not `microsoft` — the frontend maps
this in `Auth.tsx`.

## 6. Authorisation and multi-tenancy

**Every row belongs to an organization.** `organization_id` is on virtually
every table, and every repository finder takes it. A repository method that
takes only an id is a cross-tenant read waiting to happen.

Roles: `ADMIN`, `MANAGER`, `SALES_REP`, `SUPPORT_AGENT`, `MARKETING`.

Two scoping rules layered on top of the organization:

- `SALES_REP` and `SUPPORT_AGENT` see **only leads assigned to them**. This is
  applied as a Specification ANDed in *before* any user-supplied filter, so a
  rep filtering by another rep's id gets an empty page rather than an error or
  someone else's data.
- Lead assignment and manager review are `ADMIN`/`MANAGER` only.

Lookups outside a caller's scope return **404, not 403** — a 403 confirms the
record exists.

## 7. Database and migrations

**Flyway**, `backend/src/main/resources/db/migration/`. Migrations are
forward-only and never edited once applied — Flyway records a checksum, and
changing an applied file breaks startup.

Hibernate runs with `ddl-auto: validate`: it never changes the schema, it only
refuses to start if the entities and tables disagree. The schema is defined by
the migrations, not by the entity classes.

| Migration | What it added |
|---|---|
| V1–V8 | Leads, organizations, users, login history, audit log |
| V9 | Accounts, contacts, deals, cases, campaigns |
| V10 | Workflow definitions, lead meetings |
| V11 | RPA bots and runs |
| V12 | OAuth columns; made `password_hash` nullable for SSO-only users |
| V13 | Default currency INR |
| V14 | 17 deal-scoring inputs + score output on `deals` |
| V15 | Lead qualification/assignment/contact/conversion; opportunity fields; 6 pipeline tables |
| V16 | Converted three `jsonb` string-list columns to `text[]` |

**Why V16 exists** — a worked example of a real trap. Mapping a Java
`List<String>` with `@JdbcTypeCode(SqlTypes.JSON)` updates the recommended JDBC
type for `List<String>` **process-wide**. That broke `users.permissions`, an
unrelated `text[]` column, at schema validation. One entity silently redefined
the mapping for another. Lesson: in this codebase, string lists are `text[]`.

## 8. The lifecycle of one request

Take `POST /api/leads`:

```
Browser
  └─ queries.ts  useCreateLead()          TanStack Query mutation
      └─ apiClient.ts  request()          adds Bearer token, credentials: include
          └─ CorsConfig                   allows the :5173 origin
              └─ JwtAuthenticationFilter  validates JWT → AuthenticatedUser principal
                  └─ SecurityConfig       checks the route is authenticated
                      └─ LeadController   binds + validates @RequestBody
                          └─ LeadService  business logic, @Transactional
                              ├─ AiScoringClient → AiChatClient → Groq   (score + qualify)
                              ├─ autoAssignIfQualified                    (if qualified)
                              └─ LeadRepository.save()                    → Neon
                          └─ LeadMapper   entity → LeadResponse
  ◄──────────────────────────────────────  JSON
  └─ onSuccess → invalidateQueries(["leads"]) → list and stats refetch
```

Every module follows this shape:

```
Controller  → HTTP only: routing, validation, status codes
Service     → business rules, transactions, orchestration
Repository  → data access (Spring Data JPA)
Entity      → the table
Dtos        → the wire shape, never the entity
Client      → an outbound call to something external
```

**Entities are never returned to the browser.** Every response goes through a
DTO. This is what stops a schema change from silently becoming an API change,
and stops `password_hash` from ever reaching a response body.

## 9. Error handling and degradation

The AI parts are treated as **enhancements that may be unavailable**, never as
required dependencies:

| Failure | What happens |
|---|---|
| LLM unreachable during lead scoring | Lead saves unscored; scored on a later edit |
| LLM unreachable during deal analysis | Falls back to keyword matching; analysis marked `DEGRADED` |
| XGBoost service down | Meeting output, parameters and features all still save; deal keeps its previous score |
| Both down | The CRM still works as a CRM |

`ApiExceptionHandler` turns `ResponseStatusException` into a consistent JSON
error body. Services throw `ResponseStatusException` with an HTTP status rather
than inventing exception types.

## 10. Where to go next

| Document | Covers |
|---|---|
| [BACKEND-GUIDE.md](BACKEND-GUIDE.md) | Every backend package and file |
| [FRONTEND-GUIDE.md](FRONTEND-GUIDE.md) | Every frontend file |
| [AI-INTEGRATION.md](AI-INTEGRATION.md) | Every place the LLM and XGBoost are called |
| [LEAD-AND-DEAL-FLOW.md](LEAD-AND-DEAL-FLOW.md) | The sales workflow and its API |
| [DATABASE-SETUP.md](DATABASE-SETUP.md) | Getting PostgreSQL running |
