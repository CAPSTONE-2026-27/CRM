-- Add per-user screen permission keys
ALTER TABLE "User" ADD COLUMN "permissions" TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[];

-- Backfill existing users from their role's defaults so nobody loses access
-- on deploy. ADMIN bypasses permission checks in code but gets the full set
-- for display consistency.
UPDATE "User" SET "permissions" = ARRAY['leads','pipeline','accounts','cases','workflow','rpa','marketing','analytics','security','copilot']
  WHERE "role" IN ('ADMIN', 'MANAGER');

UPDATE "User" SET "permissions" = ARRAY['leads','pipeline','accounts','copilot']
  WHERE "role" = 'SALES_REP';

UPDATE "User" SET "permissions" = ARRAY['cases','copilot']
  WHERE "role" = 'SUPPORT_AGENT';
