# Database setup

The API uses **PostgreSQL 14+**. The schema is owned by **Flyway** migrations in
[`backend/src/main/resources/db/migration`](../backend/src/main/resources/db/migration)
and is applied automatically on startup — there is no separate migrate step.

## 1. Create the database

Pick whichever matches your setup.

### Option A — Local PostgreSQL via Homebrew (macOS)

```bash
brew install postgresql@16
brew services start postgresql@16

# Creates a database owned by your current OS user (Homebrew defaults to trust auth)
createdb crm_spring
```

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/crm_spring
    username: <your-mac-username>   # run `whoami`
    password: ""
```

### Option B — Any remote/hosted PostgreSQL (RDS, Neon, Supabase, self-managed)

```sql
CREATE DATABASE crm_spring;

-- Optional: a dedicated app user rather than an admin role
CREATE USER crm_app WITH PASSWORD 'choose-a-strong-password';
GRANT ALL PRIVILEGES ON DATABASE crm_spring TO crm_app;
```

```
jdbc:postgresql://<host>:5432/crm_spring
```

Hosted providers usually give you a URL in `postgresql://` form. JDBC needs the
`jdbc:` prefix, and credentials go in their own fields rather than in the URL.

### Option C — Docker

```bash
docker run --name crm-postgres \
  -e POSTGRES_DB=crm_spring \
  -e POSTGRES_USER=crm_app \
  -e POSTGRES_PASSWORD=choose-a-strong-password \
  -p 5432:5432 \
  -d postgres:16
```

## 2. Configure the app

Either set environment variables:

```bash
export DB_URL="jdbc:postgresql://localhost:5432/crm_spring"
export DB_USERNAME="crm_app"
export DB_PASSWORD="choose-a-strong-password"
```

or copy `backend/src/main/resources/application-local.yml.example` to
`application-local.yml` and fill it in. That file is gitignored — never commit
real credentials.

## 3. Start the API

```bash
cd backend
./mvnw spring-boot:run
```

Flyway applies every pending migration on startup, then Hibernate validates the
entity mappings against the resulting schema (`ddl-auto: validate`) and refuses
to start if they disagree — a mismatch is a real bug, not something to work
around.

## 4. Verify

```bash
curl http://localhost:8080/actuator/health     # {"status":"UP"} with db UP

psql -d crm_spring -c '\dt'                    # lists the tables
psql -d crm_spring -c 'SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;'
```

## Troubleshooting

- **`FATAL: role "postgres" does not exist`** — Homebrew creates a role named
  after your macOS user, not `postgres`. Set `DB_USERNAME` to the output of
  `whoami`.
- **`Connection refused`** — PostgreSQL isn't running. `brew services list`
  should show `postgresql@16` as `started`.
- **`Schema-validation: missing table/column`** — the entities and the database
  disagree. Check that every migration applied (see `flyway_schema_history`)
  rather than relaxing `ddl-auto`.
- **A migration failed halfway** — Flyway records it as failed and refuses to
  continue. Fix the SQL, then
  `DELETE FROM flyway_schema_history WHERE success = false;` and restart. Only
  safe on a database you're willing to rebuild.
- **Starting over** — `dropdb crm_spring && createdb crm_spring`, then restart.
  **Destructive**: this deletes everything in that database.
