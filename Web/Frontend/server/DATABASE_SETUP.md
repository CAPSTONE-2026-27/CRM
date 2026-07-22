# Database setup

This app uses **PostgreSQL 14+** with Prisma as the ORM/migration tool. The full schema (13 tables) lives in [`prisma/schema.prisma`](prisma/schema.prisma) and is applied via versioned migration files in `prisma/migrations/`.

## 1. Create the database

Pick whichever matches your setup.

### Option A — Local Postgres via Homebrew (macOS)

This is what's running in local dev right now.

```bash
brew install postgresql@16
brew services start postgresql@16

# creates a DB owned by your current OS user (Homebrew's default auth is "trust")
createdb techcrm
```

Connection string:
```
postgresql://<your-mac-username>@localhost:5432/techcrm?schema=public
```
(Run `whoami` to get `<your-mac-username>`. An explicit username is required — Prisma will fail with `P1010: User was denied access` if you omit it.)

### Option B — Any remote/hosted PostgreSQL (RDS, Supabase, Neon, self-managed, etc.)

Connect with `psql` (or your provider's SQL console) and run:

```sql
CREATE DATABASE techcrm;

-- Optional: a dedicated app user instead of using an admin/root role
CREATE USER techcrm_app WITH PASSWORD 'choose-a-strong-password';
GRANT ALL PRIVILEGES ON DATABASE techcrm TO techcrm_app;
```

Connection string:
```
postgresql://techcrm_app:choose-a-strong-password@<host>:5432/techcrm?schema=public
```

Most hosted providers give you a full connection string directly in their dashboard — that works as-is, just make sure it ends with `?schema=public` (append it if missing).

### Option C — Docker (no local Postgres install needed)

```bash
docker run --name techcrm-postgres \
  -e POSTGRES_DB=techcrm \
  -e POSTGRES_USER=techcrm_app \
  -e POSTGRES_PASSWORD=choose-a-strong-password \
  -p 5432:5432 \
  -d postgres:16
```

Connection string:
```
postgresql://techcrm_app:choose-a-strong-password@localhost:5432/techcrm?schema=public
```

## 2. Configure the app

Edit `server/.env` and set:

```
DATABASE_URL="postgresql://<user>:<password>@<host>:5432/techcrm?schema=public"
```

(`server/.env` is gitignored — never commit real credentials.)

## 3. Apply the schema

From the `server/` directory:

```bash
cd server
npm install          # if you haven't already
npx prisma migrate deploy
```

`migrate deploy` applies the existing migration files non-interactively — the right choice for a fresh database. Only use `npx prisma migrate dev` instead if this is a dev database you intend to keep evolving the schema against (it can prompt and generate new migration files).

This creates all 13 tables: `Organization`, `User`, `RefreshToken`, `Account`, `Contact`, `Lead`, `Deal`, `Case`, `Campaign`, `WorkflowDefinition`, `RpaBot`, `RpaBotRun`, `AuditLog`. Full column-level detail is in `prisma/schema.prisma`.

## 4. Verify

```bash
npx prisma studio          # opens a DB browser UI at localhost:5555
```

or directly:

```bash
psql "$DATABASE_URL" -c '\dt'    # lists all tables
```

Then start the app and confirm the API can reach the DB:

```bash
npm run dev        # starts on :4000
curl http://localhost:4000/health   # should return {"status":"ok"}
```

## Troubleshooting

- **`P1010: User was denied access on the database`** — your connection string is missing an explicit username. Add `<user>@` before the host.
- **`P1001: Can't reach database server`** — Postgres isn't running, or the host/port is wrong. For Homebrew: `brew services list` should show `postgresql@16` as `started`.
- **Migration already applied elsewhere and you need to reset**: `npx prisma migrate reset` — **destructive**, drops and recreates the database from migrations. Only run this against a database you're OK wiping.
