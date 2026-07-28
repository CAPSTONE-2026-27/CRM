# Backend migration: Node/Express → Spring Boot (complete)

Completed 2026-07-28. The Node/Express + Prisma API has been removed; the Spring
Boot backend in [`backend/`](../backend) now serves the application. This
document is kept as a record of what changed and why, for anyone reading the
history or reviving something from it.

## Why there were two backends

The React frontend was originally written against the Spring API — its
Spring-Data pagination envelope (`content` / `totalElements` / `totalPages`) and
audit fields named `action` / `entityType` / `entityId` are the giveaways. A
Node/Express reimplementation was later built and became what actually ran,
but it never matched those contracts, which caused two screens to crash on
field names the API had never returned.

Spring Boot was chosen as the single backend, and Node was retired once the
Spring API reached parity.

## What had to be built on the Spring side

Spring already had auth, users, leads (including duplicate detection Node never
had), analytics, audit, and IMAP email ingestion. Added during the migration:

| Added | Notes |
|---|---|
| Accounts, contacts, deals, cases, campaigns | V9 |
| Workflows, Lead Output (meetings) | V10 |
| RPA bots + runs | V11 |
| Google / Microsoft SSO | V12 |
| Groq / OpenAI-compatible AI client | replaced a bespoke `POST /score` service that nothing implemented |

## Decisions worth remembering

**A separate database, not a shared one.** Spring's Flyway schema
(`snake_case`, bigint identity keys) and Prisma's (`PascalCase`, cuid string
keys) were structurally incompatible — the same data could not be served by
both. Spring was pointed at its own `crm_spring` database and Flyway builds it
cleanly. This avoided rewriting identifier types across 24 Java files, which
adapting Spring to Prisma's schema would have required.

Consequence: **data created against the Node backend (in Neon) did not come
across.** It still exists in that Neon database if it is ever needed.

**The AI integration point is the OpenAI-compatible chat-completions API,** not
a bespoke inference service. A hosted API and a self-hosted vLLM/Ollama server
expose the same contract, so serving the fine-tuned model from [`ml/`](../ml)
later is a configuration change — `AI_BASE_URL`, `AI_MODEL_NAME`, `AI_API_KEY`.

**RPA bots run on Spring's own primitives** — `@Async` for event and manual
runs, `@Scheduled` for the hourly sweep — rather than a broker. Node used
BullMQ on Redis. The trade-off is documented on `BotExecutionService`: queued
work is in-process, so a restart drops anything mid-flight and there is no
cross-instance coordination.

**Sessions are `IF_REQUIRED`, not `STATELESS`.** The OAuth handshake has to hold
the authorization request across the provider round-trip. The API itself stays
token-based and the session goes unused afterwards.

## Traps hit along the way

- `backend/mvnw` was missing its executable bit, and no JDK was installed. That
  is why the Spring module had never once been compiled.
- Spring Boot 4 makes JSON binding pluggable, so `jackson-databind` is **not** a
  compile-scope transitive of `spring-boot-starter-webmvc`. It has to be
  declared explicitly.
- Constructor-injecting the OAuth success handler into `SecurityConfig` created
  a cycle (`SecurityConfig` → handler → `AuthService` → `PasswordEncoder`, which
  `SecurityConfig` declares) and the application refused to start. It is taken
  as a `@Bean` method parameter instead.
- `@Transactional` on a method called from within the same bean is bypassed by
  the proxy. Two such annotations were removed rather than left implying
  behaviour that never happened.

## Behaviour parity

Scoring drives the lead's `status` (HOT/WARM/COLD) from the AI label, matching
the Node enrichment bot, so the Leads table's Status column and its filter track
scoring rather than sitting at NEW. `aiScoreLabel` stays display text and
`status` a fixed uppercase vocabulary; the two are compared case-insensitively.
