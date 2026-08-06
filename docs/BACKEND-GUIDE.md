# Backend guide

Every package and every file in `backend/src/main/java/com/techcrm/crm/`.

Read [SYSTEM-OVERVIEW.md](SYSTEM-OVERVIEW.md) first — it explains the layering
(`Controller → Service → Repository → Entity`), authentication, and
multi-tenancy, which are assumed throughout.

**Stack:** Java 21, Spring Boot 4.1.0, Spring Security, Spring Data JPA /
Hibernate, Flyway, Lombok, PostgreSQL.

**Entry point:** `CrmApplication.java` — a standard `@SpringBootApplication`.
Nothing interesting lives here.

---

## Reading order for a newcomer

If you read the whole thing top to bottom you will drown. This order builds up:

1. `config/` — how requests are secured and routed
2. `auth/` — how a user becomes an `AuthenticatedUser`
3. `lead/` — the richest conventional module; the pattern everything else follows
4. `ai/` — the LLM client every AI feature sits on
5. `dealflow/` — the most complex module, and the reason the project exists
6. Everything else — variations on the `lead/` pattern

---

## `config/` — cross-cutting setup

| File | Purpose |
|---|---|
| `SecurityConfig.java` | The Spring Security filter chain. Declares which routes are public (`/api/auth/login`, `/api/auth/signup`, `/api/auth/refresh`, the OAuth handshake) and that everything else needs authentication. Registers `JwtAuthenticationFilter` and `OAuth2SuccessHandler`. |
| `CorsConfig.java` | Allows the Vite dev origin (`:5173`) with credentials, so the httpOnly refresh cookie is accepted cross-origin. |
| `AsyncConfig.java` | Thread pools for `@Async` work — background lead scoring and RPA bot runs. Replaces what BullMQ/Redis did in the original Node backend. |
| `WebConfig.java` | Pageable defaults. |
| `ApiExceptionHandler.java` | `@RestControllerAdvice` turning exceptions into consistent JSON error bodies, so the frontend can always read `body.error`. |

> `OAuth2SuccessHandler` is taken as a **`@Bean` method parameter** in
> `SecurityConfig`, not `@Autowired` into a field. Injecting it directly creates
> the cycle `SecurityConfig → OAuth2SuccessHandler → AuthService →
> PasswordEncoder → SecurityConfig` and the context fails to start.

## `auth/` — identity

| File | Purpose |
|---|---|
| `AuthController.java` | `/api/auth/*` — signup, login, refresh, logout, me, change-password. Owns the refresh cookie's path constant. |
| `AuthService.java` | Signup and login logic, password hashing, refresh token rotation, session revocation. |
| `AuthenticatedUser.java` | The principal on `SecurityContext`: `userId`, `organizationId`, `role`, `permissions`. Derived from the JWT and **never re-fetched per request** — the token is the source of truth until it expires. Injected into controllers with `@AuthenticationPrincipal`. |
| `JwtService.java` | Signs and verifies access tokens. |
| `JwtAuthenticationFilter.java` | Reads the `Authorization` header, validates, populates the SecurityContext. Deliberately **not** a `@Component` — it is constructed by `SecurityConfig`. As a component, Boot would also register it as a plain servlet filter and it would run twice. |
| `RefreshTokenUtil.java` | Generates opaque high-entropy tokens and hashes them (SHA-256) for storage. |
| `OAuth2SuccessHandler.java` | Runs after Google/Microsoft succeed: resolves the identity, sets the refresh cookie at the **same path** `AuthController` uses, redirects to `{frontend-url}/?signed_in=1`. |
| `OAuthLoginService.java` | Maps a provider identity to a CRM user in three cases: returning OAuth user (matched on provider + account id), existing local account with the same email (links the provider), or brand-new user (creates user + organization). |
| `TokenPair.java` | Access + refresh token holder. |

> **The cookie path bug, worth knowing.** `AuthController` sets the refresh
> cookie at `/api/auth`; an early version of `OAuth2SuccessHandler` set it at
> `/`. Browsers keep both, and `/api/auth/refresh` read the stale one. Sign-in
> appeared to work then silently failed on the next reload. Both now use
> `AuthController.REFRESH_COOKIE_PATH`.

## `user/` — accounts, roles, permissions

| File | Purpose |
|---|---|
| `User.java` | The user entity. `password_hash` is **nullable** — SSO-only users never have one. `permissions` is a `text[]` column. |
| `Role.java`, `UserStatus.java` | Enums. |
| `UserController.java` / `UserService.java` | CRUD, role changes, status toggles, admin password reset. |
| `UserRepository.java` / `UserSpecifications.java` | Queries. Every finder excludes soft-deleted rows and is organization-scoped. |
| `PermissionDefaults.java` | Server-side mirror of the frontend's `ROLE_DEFAULT_PERMISSIONS`, used **only** to seed a new user's permissions at creation. Runtime checks use the stored column, not this. |
| `RefreshToken.java` / `RefreshTokenRepository.java` | Hashed refresh tokens, for revocation. |
| `LoginHistory.java` / `LoginHistoryRepository.java` | Login audit trail; feeds the Security screen. |
| `dto/` | `UserUpdateRequest` (partial update — null means leave alone), `ResetPasswordRequest`, `ResetPasswordResponse` (the plaintext temporary password is returned exactly once and never logged). |

## `lead/` — Lead Management (the reference module)

The largest conventional module. If you understand this one, the rest follow.

**Core:**

| File | Purpose |
|---|---|
| `Lead.java` | The entity. Holds contact details, AI score, **qualification** (status / probability / reasoning), **assignment** (assignee, timestamp, status), **contact status**, and **conversion** pointers. |
| `LeadController.java` | `/api/leads` — CRUD, search, stats, import, export, and the flow endpoints `assign`, `contact-status`, `convert`. |
| `LeadService.java` | The biggest service in the codebase. Create/update/patch, role-scoped search, auto-assignment, contact status, stats, bulk delete. |
| `LeadRepository.java` | Spring Data queries. |
| `LeadMapper.java` | Entity → `LeadResponse`. |

**Request/response shapes:**

| File | Purpose |
|---|---|
| `LeadRequest.java` | Full create/update. An omitted field **blanks** the column. |
| `LeadPatchRequest.java` | Partial update. `null` means leave alone. The one exception is `assignedToId`, where blank clears the assignment and null leaves it. |
| `LeadResponse.java` | What the API returns. |
| `LeadSearchCriteria.java` | Normalises every text param once (trim + collapse whitespace) so `"  acme  "` and `"acme"` behave identically. |
| `LeadSpecifications.java` | Composable JPA Specifications. Text predicates lower-case **server-side** via `criteriaBuilder.lower(...)`, not in Java, so the database can use an index. |
| `LeadFlowDtos.java` | Assign / contact-status / convert request and response records. |
| `LeadStatsResponse.java` | Totals for the metric tiles — organization+role scoped but **ignoring current filters**, because tiles should show true totals, not the count of whatever page is loaded. |

**AI and bulk paths:**

| File | Purpose |
|---|---|
| `AiScoringClient.java` | Prompts the LLM to score **and qualify** a lead in one call. See [AI-INTEGRATION.md](AI-INTEGRATION.md). |
| `AiScoreResult.java` | Score, label, reason, qualification status, probability, reasoning. |
| `LeadImportService.java` | CSV import. Accepts a loose header row — both `fullName` and `Full Name` style. Rows are saved unscored and scored asynchronously, because scoring 500 rows synchronously would take an hour. |
| `LeadCsvExporter.java` | RFC 4180 CSV export. Quotes **every** value unconditionally: notes and reasoning routinely contain commas, quotes and newlines, and "quote only when necessary" is the rule that eventually gets one case wrong and corrupts every row after it. |
| `LeadConversionService.java` | **Flow step 6.** Lead → opportunity. Finds or creates an account by company name, creates the deal, carries the lead score across, and links both directions. Refuses (409) if the lead is unqualified, already converted, or the customer has not agreed to meet. |
| `CsvImportRequest`, `LeadImportResult`, `RowWarning`, `FailedRow`, `BulkDeleteRequest`, `BulkDeleteResult`, `LeadCreationResult` | Small payload records. |

## `dealflow/` — the AI analysis pipeline

The heart of the system. One meeting write-up produces a full chain:

```
MeetingOutput → DealAnalysis → ExtractedParameter[] → FeatureSet → DealPrediction
```

Each submission creates a **new version** of that whole chain. Nothing is
overwritten, so a deal's score progression stays readable meeting by meeting.

**Orchestration:**

| File | Purpose |
|---|---|
| `DealFlowService.java` | Runs the pipeline end to end, persisting each stage before the next so a partial chain can be inspected rather than lost. Also serves the workspace read and manager reviews. |
| `DealFlowController.java` | `/api/deals/{id}/workspace`, `/meeting-outputs`, `/review`, `/onboarding`, and `/api/deal-flow/parameters`. |
| `DealFlowDtos.java` | Every wire shape for the pipeline. |

**The AI stages:**

| File | Purpose |
|---|---|
| `DealAnalysisClient.java` | **Step 5–6.** Sends the meeting write-up to the LLM, gets back 14 business parameters each with a value, confidence and explanation. |
| `HeuristicMeetingAnalyzer.java` | The fallback when the LLM is unreachable. Keyword matching, with no pretence otherwise — every value carries a low confidence and an explanation saying where it came from. |
| `DealParameters.java` | **The contract.** The 14 parameter names, the exact values the XGBoost bundle accepts, numeric encodings, and `snap()` — which pulls whatever the LLM said onto the accepted list. |
| `FeatureEngineeringService.java` | **Step 7.** Turns extracted parameters into the 17 model inputs *and* a 0–1 numeric vector. Derives `total_meetings`, `lead_score` and `engagement_score` from what the CRM already knows. |
| `DealPredictionService.java` | **Steps 8–10.** Interprets the score: risk level, positive/negative factors, and the manager recommendation thresholds. Deliberately separate from the model so a sales leader can change it without a retrain. |

**Entities and repositories** — one pair per stage:

| Entity | Table | Holds |
|---|---|---|
| `MeetingOutput` | `deal_meeting_outputs` | The 16-field write-up, versioned per deal |
| `DealAnalysis` | `deal_analyses` | Status (`SUCCEEDED`/`DEGRADED`), model, latency, **raw reply verbatim** |
| `ExtractedParameter` | `deal_extracted_parameters` | One row per parameter, with confidence and explanation |
| `FeatureSet` | `deal_feature_sets` | Numeric features + the exact labels sent to XGBoost + imputed fields |
| `DealPrediction` | `deal_predictions` | Score, win probability, band, risk, factors, model version |
| `ManagerReview` | `deal_manager_reviews` | Decision, **frozen** recommendation, override, comments |

> **Why the raw LLM reply is stored.** When an extraction looks wrong, the only
> way to tell a bad prompt from a bad parse is to read what actually came back —
> and by then the API call is long gone.

> **Why parameters are rows, not a JSON blob.** So you can ask "show me every
> deal where budget status was extracted below 0.5 confidence" across the whole
> organization. That query is the entire reason for storing confidence.

> **Repositories must be top-level interfaces.** They were briefly grouped as
> nested interfaces inside one file; Spring Data does not scan nested interfaces
> and every bean silently failed to exist.

## `deal/` — deals and the pipeline board

| File | Purpose |
|---|---|
| `Deal.java` | The entity. Conventional deal fields, plus the 17 manual scoring inputs (V14), the denormalised newest prediction, opportunity id, lead link, meeting scheduling, and closing reason. |
| `DealStages.java` | The stage vocabulary in workflow order, `isClosed()`, and `opportunityReference(id)` → `"OPP-000042"`. |
| `DealController.java` / `DealService.java` | CRUD, drag-and-drop stage changes, meeting scheduling, closing. Closing as won initiates onboarding — placed in the service so *every* path that closes a deal gets the handover. |
| `DealDtos.java` | Request/response records. |
| `DealScoringClient.java` | The HTTP client for the XGBoost service. Returns `null` on any failure so scoring never blocks saving. |

> `deals` carries a **denormalised copy** of the newest prediction (`deal_score`,
> `win_probability`, `risk_level`). `deal_predictions` remains the record of
> truth; the copy exists so the pipeline board can sort and colour thousands of
> cards without a correlated subquery per row.

## `meeting/` — Lead Output (distinct from dealflow)

Post-meeting records against a **lead**, before it becomes an opportunity. A
simpler, older sibling of `dealflow/`: one AI call produces a summary and a
re-score, rather than 14 structured parameters.

| File | Purpose |
|---|---|
| `LeadMeeting.java` | Append-only meeting rows with before/after score. |
| `LeadMeetingController.java` | `/api/leads/{id}/meetings` — history, `analyze` (preview, persists nothing), and save. |
| `LeadMeetingService.java` | Two-phase flow so the rep can review and edit the AI summary before committing. On save, snapshots the previous score **from the lead**, not from the request, so a crafted payload cannot rewrite the scoring trail. |
| `MeetingAnalysisClient.java` | The LLM call — summary and re-score together, because the score must be justified by the same notes the summary is drawn from. |
| `MeetingDtos.java` | Input, preview and persisted response records. |

## `ai/` — the LLM plumbing

| File | Purpose |
|---|---|
| `AiChatClient.java` | The single outbound LLM client. Talks to **any** OpenAI-compatible chat-completions endpoint. `complete()` returns `null` on failure; `stream()` pushes deltas to a callback. |
| `AiJson.java` | Extracts a JSON object from a model reply. Instruction-tuned models wrap JSON in ``` fences or pad it with prose despite being told not to, so it takes the first `{` to the last `}` rather than trusting the whole reply to parse. |
| `DealCoachController.java` | The in-app assistant. `POST /api/copilot/chat`, streaming over SSE. Only forwards `user`/`assistant` roles so a crafted request cannot inject a second system prompt. |

## `onboarding/` — post-sale handover

| File | Purpose |
|---|---|
| `CustomerOnboarding.java` | Opened automatically on Closed Won. |
| `CustomerOnboardingService.java` | Idempotent: a deal is onboarded once however many times it is re-saved as won — enforced by a unique index as well as the read-then-write check, since a race would slip past the check alone. |
| `CustomerOnboardingRepository.java` | Queries. |

## Conventional CRM modules

All five follow the identical `Entity / Controller / Service / Repository / Dtos`
shape. Read one and you have read all of them.

| Package | Endpoint | Notes |
|---|---|---|
| `account/` | `/api/accounts` | Companies. Self-referencing `parent_account_id` for hierarchies. |
| `contact/` | `/api/contacts` | People at accounts. |
| `support/` | `/api/cases` | Support tickets. The entity is `CaseRecord`, not `Case` — `case` is a Java keyword. `case_number` is per-organization and allocated in the service. |
| `campaign/` | `/api/campaigns` | Marketing campaigns. |
| `workflow/` | `/api/workflows` | Workflow definitions. Node lists are stored as JSONB — an ordered document, never queried field-wise. |

## `rpa/` — bot control room

| File | Purpose |
|---|---|
| `RpaBot.java`, `RpaBotRun.java` | Bot registry and run history. |
| `RpaBotService.java`, `BotExecutionService.java` | Executes the three built-in bots on Spring's `@Async` and `@Scheduled` primitives. The Node original used BullMQ on Redis; this needs no extra infrastructure. |
| `RpaBotController.java`, `RpaBotRunController.java`, `RpaDtos.java` | API surface. Run responses embed a bot summary so the control room can label a run without a second request. |

## `email/` — inbound lead capture

| File | Purpose |
|---|---|
| `ImapPollingService.java` | Polls one configured mailbox for unread mail, creating a lead per message. **Disabled by default** — blank credentials keep the poller off without crashing startup. |
| `EmailLeadParser.java` | Heuristic extraction from an email into a lead. Deliberately no ML — see the class comment for the scope decision. |
| `PastedEmailRequest.java` | Payload for the manual "paste an email in" flow, which runs the same parser synchronously so the result is visible immediately. |

> A shared mailbox has no per-message tenant signal, so inbound email is
> attributed to a single configured `organization-id`. One mailbox = one
> organization, by assumption.

## `analytics/`, `audit/`, `org/`, `common/`

| Package | Contents |
|---|---|
| `analytics/` | `AnalyticsController` / `AnalyticsService` and the dashboard response records. Aggregates for the dashboard and reporting screens. |
| `audit/` | `AuditLog` entity, `AuditLogService` (writes), `AuditLogQueryService` (reads), controller and response. Feeds the Security screen. Columns are `action` / `entityType` / `entityId`. |
| `org/` | `Organization` entity and repository. The tenant root. |
| `common/` | `PagedResponse` — a stable pagination envelope, deliberately **not** Spring Data's raw `Page` JSON, which leaks internal pageable/sort fields and changes shape between versions. |

---

## Conventions to follow when adding code

1. **Scope every query by `organizationId`.** No exceptions.
2. **Never return an entity.** Add a DTO.
3. **Throw `ResponseStatusException`** with the right status rather than a custom exception type.
4. **Return 404, not 403,** for records outside the caller's scope.
5. **External calls fail soft.** Return `null`, log a warning, let the feature degrade.
6. **Schema changes go in a new migration.** Never edit an applied one.
7. **String lists are `text[]`**, mapped with `@JdbcTypeCode(SqlTypes.ARRAY)` — see the V16 note in [SYSTEM-OVERVIEW.md](SYSTEM-OVERVIEW.md).
