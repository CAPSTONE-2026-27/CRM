# TechCRM — the complete system workflow

How a stranger's enquiry becomes a signed customer, every step it passes
through, and which part of the system moves it.

Written against the code on `main` as of 26 September 2026. Where the system
does less than its UI suggests, this document says so.

---

## 1. The three processes

All three must be running for the full workflow. The CRM degrades rather than
fails when the two AI services are down.

| Process | Port | Stack | Start with |
|---|---|---|---|
| Frontend | 5173 | React + Vite | `cd frontend && npm run dev` |
| Backend API | 8080 | Java 21 / Spring Boot 4.1 | `cd backend && ./mvnw spring-boot:run` |
| Deal scoring model | 8000 | Python / FastAPI (XGBoost) | `cd XgBoost && python serve_api.py` |

Plus three things the system talks to but does not run: **Neon** (hosted
PostgreSQL, the only persistent store), an **LLM endpoint** (any
OpenAI-compatible chat API), and **Mailjet** (transactional email).

```
  Browser ──► React SPA ──/api/*──► Spring Boot ──JDBC──► Neon PostgreSQL
                                        │
                        ┌───────────────┼────────────────┐
                        ▼               ▼                ▼
                   LLM (chat)     XGBoost :8000      Mailjet (email)
```

**SAP Build Process Automation** sits outside all of this and drives the
document bots over the same REST API a human uses.

## 2. Who does what

| Role | In the workflow |
|---|---|
| `ADMIN` | Everything, plus user management |
| `MANAGER` | Reviews deal analyses, sees the whole pipeline |
| `SALES_REP` | Owns leads and deals end to end |
| `SUPPORT_AGENT` | Handles support cases |
| `MARKETING` | Campaigns |

Every request is scoped to the caller's **organization**. A record belonging to
another organization answers `404`, never `403` — the CRM will not confirm that
someone else's record exists.

---

## 3. The journey, end to end

```
 (1) LEAD CAPTURE          manual form · pasted email · CSV import · IMAP poller
        │
 (2) AI LEAD SCORING       score 0-100 + AI insight, recomputed on edits
        │
 (3) QUALIFY & ASSIGN      status NEW → QUALIFIED, owner set, contact status tracked
        │
 (4) CONVERT               account + contact + deal created, lead closed
        │                  deal stage: OPPORTUNITY_CREATED
 (5) MEETING               scheduled, then its outcome recorded
        │
 (6) DEAL ANALYSIS         XGBoost win probability + LLM analysis → manager review
        │
 (7) PIPELINE              PROSPECTING → QUALIFICATION → PROPOSAL
        │
 (8) PROPOSAL BOT          generate DOCX+PDF → email the customer the PDF
        │
 (9) NEGOTIATION           agreed price and terms recorded on the deal
        │
(10) CONTRACT BOT          generate DOCX+PDF → email the customer the PDF
        │
(11) CLOSE                 CLOSED_WON or CLOSED_LOST, with a reason
        │
(12) ONBOARDING            opens automatically on Closed Won
```

Support cases, campaigns, the RPA bots page, the workflow engine, analytics and
the audit log run alongside this line rather than inside it — see §6.

---

## 4. Stage by stage

### (1) Lead capture

Four ways in, all landing in the same `leads` table with `status = NEW` and
`contactStatus = NOT_CONTACTED`:

| Route | Endpoint | Used by |
|---|---|---|
| Manual form | `POST /api/leads` | Sales rep in the UI |
| Pasted email | `POST /api/leads/from-email` | Rep pasting an enquiry; parsed into fields |
| CSV import | `POST /api/leads/import` | Bulk load, reports per-row failures |
| Mailbox poller | *(none — scheduled)* | `ImapPollingService`, polls IMAP every 2 minutes |

The poller is **off by default**. It needs a mailbox and an organization id in
`application-local.yml`; without them it logs a warning and skips.

### (2) AI lead scoring

Every new or edited lead is scored **0–100**. Scoring is asynchronous, so the
lead appears immediately and its score arrives shortly after.

- `GET /api/leads/{id}/score-timeline` — the score and how it moved
- `GET /api/leads/{id}/ai-insight` — the LLM's written assessment

**The model advises; it does not decide.** The probability and its reasoning are
recorded, but the verdict stays `PENDING` until a person presses Qualify or
Disqualify in the UI. If the LLM or model service is unreachable
the lead still saves; it simply stays unscored, and the **Lead enrichment bot**
(§6) sweeps unscored leads later.

### (3) Qualify and assign

| Action | Endpoint | Effect |
|---|---|---|
| Qualify | `POST /api/leads/{id}/qualify` | Human verdict: `QUALIFIED` or `UNQUALIFIED` |
| Assign | `POST /api/leads/{id}/assign` | Sets the owning sales rep |
| Contact outcome | `POST /api/leads/{id}/contact-status` | `NOT_CONTACTED` → `MEETING_SCHEDULED` / `INTERESTED` / `NO_RESPONSE` / `NOT_INTERESTED` |

A high score means "convert this into an opportunity"; a low score means "keep
nurturing" — the follow-up bot watches leads that go quiet.

### (4) Conversion

```
POST /api/leads/{id}/convert        (no request body — the lead id is the input)
```

On the pipeline board, the Prospecting, Qualification and Meeting columns hold
open leads (`GET /api/leads/pipeline`, moved with `PATCH /api/leads/{id}/stage`).
Dragging a lead into Opportunity calls this endpoint. One transaction creates
the records and closes the lead:

```
Lead ──► Account   (the company — reused if one with the same name exists;
     │              the lead's billing address fills it if it has none)
     ├─► Contact   (the person — reused if the account already has their email)
     └─► Deal      (the opportunity, stage OPPORTUNITY_CREATED,
                    primary contact = that Contact)
```

The deal's primary contact is who proposals and contracts are addressed to. It
can be changed with `PUT /api/deals/{id}/primary-contact` (or the deal
workspace); when unset, generation falls back to the account's primary contact.

The response returns `dealId` — the id every later step uses. The lead keeps a
pointer to the deal, so the product it named still reaches the contract
schedule much later.

### (5) Meeting

`POST /api/deals/{id}/schedule-meeting` records when, how, and who attends. It
moves a deal still at Prospecting or Qualification to `MEETING_SCHEDULED`; a
deal at Opportunity or later keeps its stage. Lead-stage meetings have their own API
under `/api/leads/{leadId}/meetings`.

### (6) Deal analysis and manager review

After the meeting the rep records what came out of it:

```
POST /api/deals/{dealId}/meeting-outputs      what the customer said
        │
        ├─► XGBoost :8000      → win probability
        └─► LLM                → written analysis, risks, next steps
        │
GET  /api/deals/{dealId}/workspace            everything about the deal in one call
POST /api/deals/{dealId}/review               manager's decision
```

`GET /api/deal-flow/parameters` returns the controlled vocabulary the UI offers
for these inputs, so the front end never invents values the analysis cannot read.

### (7) Pipeline stages

`PATCH /api/deals/{id}` moves a deal. The board's vocabulary is fixed:

```
OPPORTUNITY_CREATED → MEETING_SCHEDULED → PROSPECTING → QUALIFICATION
   → PROPOSAL → NEGOTIATION → CONTRACT → CLOSED_WON | CLOSED_LOST
```

**Only a person moves a deal between stages.** No bot, model or document ever
advances the pipeline — that decision stays with the sales executive.

### (8) Proposal bot

Fires when a deal reaches **PROPOSAL**. Driven by SAP BPA over three calls:

```
POST /api/proposals/generate            { "dealId": 31 }   → GENERATING, then GENERATED
GET  /api/proposals/{id}/status                            → poll
POST /api/proposals/{id}/send-email                        → PDF to the customer
```

Generation assembles the customer, contact, owner and priced line items, renders
a DOCX through docx4j, converts it to PDF with headless LibreOffice, and stores
both. Pricing is **frozen** onto the proposal, so later edits to the lead or deal
cannot rewrite a quotation the customer has already seen.

Only one live proposal per deal, enforced by a database index as well as by a
check, so two simultaneous retries cannot both produce one.

### (9) Negotiation

What the parties agree is recorded **on the deal** (migration `V27`):

| Field | Meaning |
|---|---|
| `deals.value` | The agreed price — editing it during negotiation *is* the negotiation |
| `deals.payment_terms` | Agreed payment terms, or `NULL` for the configured default |
| `deals.delivery_timeline` | Agreed delivery schedule, or `NULL` for the default |
| `deals.terms_agreed_at` | When the terms were last recorded |

The proposal keeps its original frozen figures, so the earlier offer stays
auditable while the contract formalises the newer agreement.

### (10) Contract bot

Same shape as the proposal bot, and eligible from stages **CONTRACT** or
**CLOSED_WON** — deliberately later than the proposal bot, so a contract cannot
go out while the price is still being argued:

```
POST /api/contracts/generate            { "dealId": 31 }   → 202 Accepted, DRAFTING
GET  /api/contracts/{id}/status                            → poll until GENERATED
POST /api/contracts/{id}/send-email                        → PDF to the customer
```

**Generation is asynchronous and answers in about a second.** A cold render takes
longer than the 30 seconds SAP BPA waits before abandoning a call, so the
endpoint reserves the contract, returns `202`, and renders in the background.

**The email is sent by the backend, not by SAP.** SAP BPA truncates text values
at 1,024 characters and a contract PDF is ~110,000 characters as base64, so a
document routed through the workflow would arrive corrupted while still
reporting success. SAP decides *when*; the backend, which holds the PDF, sends it
through Mailjet. Sending marks the contract and the deal `SENT`, and a retry
returns `ALREADY_SENT` rather than emailing the customer twice.

The email wording lives in [`backend/email-templates/`](../backend/email-templates)
and is re-read on every send, so it can be changed while the backend is running.

> **No e-signature.** The customer replies by email to accept or reject, and the
> sales executive records the outcome. An earlier Documenso integration was
> removed; its database columns remain, unused, on `contracts` and `proposals`.

### (11) Close

`POST /api/deals/{id}/close` with `CLOSED_WON` or `CLOSED_LOST` and a reason.

### (12) Customer onboarding

Moving a deal to **CLOSED_WON** opens an onboarding record automatically:

```
INITIATED → IN_PROGRESS → COMPLETED | CANCELLED
GET/PATCH /api/deals/{dealId}/onboarding
```

---

## 5. Status vocabularies

| Thing | Values |
|---|---|
| Lead status | starts `NEW`; qualification is a separate verdict — `PENDING` until a human presses Qualify, then `QUALIFIED` or `UNQUALIFIED` |
| Lead contact status | `NOT_CONTACTED`, `MEETING_SCHEDULED`, `NO_RESPONSE`, `INTERESTED`, `NOT_INTERESTED` |
| Lead score label | `HOT`, `WARM`, `COLD` |
| Deal stage | the nine above |
| Proposal | `GENERATING` → `GENERATED` → `SENT`, plus `FAILED`, `SUPERSEDED` |
| Contract | `DRAFTING` → `GENERATED` → `SENT`, plus `FAILED`, `SUPERSEDED` |
| Onboarding | `INITIATED`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED` |

`VIEWED`, `SIGNED` and `REJECTED` still exist on contracts and proposals, and
older records carry them, but nothing sets them since the e-signature
integration was removed.

Contract and proposal progress is mirrored onto the deal as
`deals.contract_status` and `deals.proposal_status` — a **second axis**, so a
deal can sit in Negotiation with a contract already with the customer.

---

## 6. What runs alongside

### Support cases
`/api/cases` — create, list, update, assign, with priority and status. Raised
by support agents against an account.

### Campaigns
`/api/campaigns` — marketing campaigns, five endpoints, independent of the deal
pipeline.

### RPA bots page
`/api/rpa-bots` is a **registry** of bots (name, platform, type, environment,
version, status) plus a run history at `/api/rpa-bot-runs`. Registering a bot
records metadata; it does not connect to UiPath or any external RPA platform.

**Three bot names actually execute in-process**, and everything else registered
runs as a no-op that still records a run:

| Bot | What it really does | Trigger |
|---|---|---|
| Lead enrichment bot | Queues scoring for unscored leads | `POST /api/rpa-bots/{id}/run` |
| Follow-up sequencing bot | Finds stale leads needing follow-up | Manual, **and hourly** (`@Scheduled`) |
| Case routing bot | Nothing to sweep — routing happens when a case is created | Manual |

### Workflow engine page
`/api/workflows` stores workflow **definitions** — name, trigger event, scope,
run mode, active flag and a list of nodes — and can activate or deactivate them.

> **There is no execution engine.** No code outside the workflow package reads a
> definition or its `triggerEvent`, so an active workflow never fires. The page
> is a designer whose output nothing yet consumes.

### Analytics and audit
- `GET /api/analytics/dashboard` — headline metrics for the dashboard
- `GET /api/audit-log` — who did what, written by every significant action
  (contract generated, contract emailed, send failed, …)

---

## 7. Which part moves the work

| Moved by | Steps |
|---|---|
| **A person** | Qualify, assign, record contact outcome, convert, schedule meeting, record meeting outputs, manager review, **every pipeline stage change**, negotiate terms, close the deal |
| **SAP BPA** (external) | Proposal bot and contract bot: generate → poll status → send email |
| **The backend, automatically** | Lead scoring, deal analysis, document rendering, sending customer email, opening onboarding on Closed Won, audit entries |
| **In-app scheduler** | Hourly follow-up sweep; IMAP mailbox poll every 2 minutes when enabled |

---

## 8. Where it lives in the code

| Area | Package |
|---|---|
| Lead capture, import, export | `lead` |
| Lead scoring and AI insight | `lead.score`, `ai` |
| Conversion | `lead.LeadConversionService` |
| Accounts, contacts | `account`, `contact` |
| Deals and pipeline | `deal` |
| Meetings | `meeting` |
| Deal analysis, workspace, review, onboarding API | `dealflow` |
| Proposal bot | `proposal`, `proposal.email` |
| Contract bot | `contract`, `contract.email` |
| Customer onboarding | `onboarding` |
| Support cases, campaigns | `support`, `campaign` |
| RPA bots, workflow definitions | `rpa`, `workflow` |
| Analytics, audit | `analytics`, `audit` |
| Auth, users, organizations | `auth`, `user`, `org` |

---

## 9. Honest gaps

1. **The workflow engine does not run anything.** Definitions are stored and can
   be activated; nothing executes them.
2. **The RPA bots page is mostly a registry.** Three named bots do real work
   in-process; no external RPA platform is integrated despite the `platform`
   field.
3. **No e-signature.** Acceptance is a human step: the customer replies, the rep
   records it. An Accept/Reject link in the email has been designed but not built.
4. **`VIEWED` / `SIGNED` / `REJECTED`** are unreachable statuses kept for older
   records.
5. **The detailed module docs live on the `RPA` branch, not `main`** —
   `SYSTEM-OVERVIEW.md`, `LEAD-AND-DEAL-FLOW.md`, `AI-INTEGRATION.md`,
   `CONTRACT-GENERATION.md`, `PROPOSAL-GENERATION.md`, the backend and frontend
   guides, and both Postman collections. They have not been merged here.
