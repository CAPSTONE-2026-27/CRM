# CRM API — Spring Boot implementation

The original Java implementation of the CRM API: Spring Boot 3, Spring Security
with JWT, JPA, and Flyway migrations (`V1`–`V8` in
[`src/main/resources/db/migration`](src/main/resources/db/migration)).

## Status

**Not the running backend.** The application currently runs against
[`../backend`](../backend) (Node/Express + Prisma), which serves the same API
surface the frontend consumes. This module is kept for reference and is not
started as part of normal development.

Two consequences worth knowing before you use it:

- It is configured for a **local** PostgreSQL (`jdbc:postgresql://localhost:5432/techcrm`
  in [`application.yml`](src/main/resources/application.yml)) and manages its schema with
  **Flyway**, whereas the Node backend uses **Prisma** migrations against the hosted
  database. Pointing both at the same database will cause them to fight over the schema.
- Its API shapes are the ones the frontend was originally written against —
  Spring Data pagination (`content` / `totalElements` / `totalPages`) and audit
  fields named `action` / `entityType` / `entityId`. The Node backend now matches
  the frontend's expectations directly, so the two are **not** drop-in equivalents.

## Running it

Requires **JDK 17+** and a local PostgreSQL database named `techcrm`.

```bash
./mvnw spring-boot:run
```

Set the database password via the `DB_PASSWORD` environment variable
(`application.yml` defaults it to `changeme`).

To use this instead of the Node backend, point the frontend at it with
`VITE_API_URL` and stop the Node API so the two don't both bind port 4000.
