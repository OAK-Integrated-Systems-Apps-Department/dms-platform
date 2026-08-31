-- Adds real first_name/last_name columns instead of deriving them from
-- display_name at write time by naively splitting on whitespace (broken
-- for single-word names, where last_name silently became '', and for
-- names with more than two parts). first_name/last_name are now the
-- source of truth; display_name is computed from them in the application
-- layer (see User.displayName(firstName, lastName)) and kept as a stored,
-- indexable/queryable column rather than computed on every read.

ALTER TABLE users
    ADD COLUMN first_name VARCHAR(255),
    ADD COLUMN last_name  VARCHAR(255);

-- Backfill existing rows using the same split display_name previously
-- used, so no existing account is left with a null first_name. This is a
-- one-time best-effort reconstruction, not a claim that it's correct for
-- every existing name - operators should spot-check names that clearly
-- got mis-split (single-word names now have an empty last_name, same as
-- they always effectively did).
UPDATE users
SET first_name = split_part(display_name, ' ', 1),
    last_name  = CASE
                     WHEN position(' ' IN display_name) > 0
                         THEN substring(display_name FROM position(' ' IN display_name) + 1)
                     ELSE ''
                 END
WHERE first_name IS NULL;

ALTER TABLE users
    ALTER COLUMN first_name SET NOT NULL;
