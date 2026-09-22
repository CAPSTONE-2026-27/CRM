-- Widens users.avatar_url to TEXT.
--
-- RECONSTRUCTED, not the original script. Version 21 was applied to the shared
-- development database on 2026-09-09 but its file was never committed -- it is
-- in no branch, no commit and no stash of this repository. That made it an
-- applied-but-missing migration, which Flyway refuses to validate against, so
-- the application could not start at all.
--
-- Rebuilt from the live schema: users.avatar_url is TEXT there, and every other
-- column of that table is unchanged, so a single widening is the whole of it.
-- Behaviourally identical to whatever ran; not necessarily byte-for-byte the
-- same script.
--
-- The change itself makes sense: avatar_url holds Google profile picture URLs,
-- which carry long signed query strings and overflow a bounded VARCHAR.

ALTER TABLE users ALTER COLUMN avatar_url TYPE TEXT;

COMMENT ON COLUMN users.avatar_url IS
    'Profile picture URL. TEXT rather than VARCHAR because federated providers '
    'return long signed URLs that overflow a bounded column.';
