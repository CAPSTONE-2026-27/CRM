# Backend migration: Node/Express → Spring Boot

Decision (2026-07-28): **Spring Boot becomes the backend.** `backend-java/` is the
target; `backend/` (Node/Express + Prisma) is what currently serves the app and
stays running until the port is complete.

The live Neon database remains the source of truth — Spring is being adapted to
the existing Prisma schema rather than the data being migrated to Flyway's.

## Where things stand

`backend-java/` has **never been compiled**. There was no JDK on the dev machine,
which is why this was never caught. Nothing in it is proven to run.

## What each backend implements

| Module | Node (live) | Spring |
|---|---|---|
| Auth (local JWT) | ✅ | ✅ |
| Google / Microsoft SSO | ✅ | ❌ |
| Users | ✅ + activate/deactivate | ✅ |
| Leads (paginated, stats, import, from-email, bulk-delete) | ✅ | ✅ |
| Lead assignment rules (manager/admin only) | ✅ | ❌ |
| Analytics | ✅ | ✅ |
| Audit log | ✅ | ✅ |
| AI lead scoring | ✅ direct OpenAI-compatible client | ✅ via external `ai.service.base-url` |
| Accounts, Contacts, Deals, Cases, Campaigns | ✅ | ❌ |
| Workflows | ✅ | ❌ |
| RPA bots + runs (BullMQ/Redis) | ✅ 3 working bots | ❌ no queue at all |
| Lead Output / meetings + AI re-scoring | ✅ | ❌ |
| Assistant chat (streaming) | ✅ | ❌ |
| Email ingestion (IMAP poller) | ❌ | ✅ |

Spring covers auth, users, leads, analytics, and audit. Roughly **8 entities plus
the bot queue** need building.

## The two blocking incompatibilities

### 1. Identifier types

| | Spring (JPA) | Prisma (Neon) |
|---|---|---|
| Primary keys | `Long`, `@GeneratedValue(IDENTITY)` → `1, 2, 3` | `String` cuid → `cms46e48m0009u023db4vmbvd` |
| Foreign keys | `Long assignedToId` | `String assignedToId` |

All 6 Spring entities use bigint identity IDs. Adapting to Prisma means changing
the ID type across **24 of 74 Java files** — entities, repositories, services,
DTOs, and controller path variables.

### 2. Table and column naming

| Flyway (Spring) | Prisma (Neon) |
|---|---|
| `leads`, `users`, `organizations`, `audit_log`, `refresh_tokens`, `login_history` | `Lead`, `User`, `Organization`, `AuditLog`, `RefreshToken`, + 9 more |
| snake_case, unquoted | PascalCase tables, camelCase columns, quoted |

Postgres folds unquoted identifiers to lowercase, so Hibernate must be configured
to quote them and preserve case — otherwise `Lead` resolves to `lead` and nothing
binds. Plan: `PhysicalNamingStrategyStandardImpl` +
`hibernate.globally_quoted_identifiers=true`, with explicit `@Table` / `@Column`
names matching Prisma exactly.

`login_history` has **no** Prisma equivalent — either add it to the Prisma schema
via a migration or drop the entity.

Flyway must **not** run against Neon; Prisma owns the schema. Disable it
(`spring.flyway.enabled=false`) or baseline it.

## Phases

1. **Toolchain** — install JDK 21, compile, fix whatever breaks. *(in progress)*
2. **Persistence** — repoint at Neon, switch IDs to `String`, add the naming
   strategy and explicit table/column mappings, disable Flyway, verify
   `ddl-auto: validate` passes against the real schema.
3. **Parity for existing screens** — frontend `VITE_API_URL` → Spring's `/api`
   prefix; verify auth, users, leads, analytics, audit end-to-end.
4. **Port the gap** — accounts, contacts, deals, cases, campaigns, workflows,
   lead meetings; then the RPA bots (needs a queue/scheduler choice) and OAuth.
5. **Retire Node** — once parity is proven.

## Notes

- Spring serves under `/api/*`; the Node API does not. The frontend's
  `VITE_API_URL` must include the prefix.
- Spring's `AiScoringClient` expects a separate inference service exposing
  `POST /score`. Node calls an OpenAI-compatible endpoint directly. Either stand
  up that service or rewrite the client to match the `AI_BASE_URL` contract in
  [`backend/.env.example`](../backend/.env.example).
- Spring's `application.yml` defaults to a **local** Postgres and `ddl-auto:
  validate`, so it will fail fast against Neon until phase 2 is done.
