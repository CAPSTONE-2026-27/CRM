-- avatar_url was sized VARCHAR(500) in V12, where the only writer was the OAuth
-- login flow storing a short Google profile-picture URL. The profile screen now
-- uploads a photo, which the browser sends as a base64 data URI running to tens
-- of thousands of characters, so every upload failed on the column width.
--
-- TEXT rather than a bigger VARCHAR: there is no length here that is both
-- generous enough for a photo and meaningful as a constraint. The real limit
-- belongs in the request DTO, where exceeding it can produce a message a person
-- can act on, instead of a driver-level range error.
ALTER TABLE users ALTER COLUMN avatar_url TYPE TEXT;
