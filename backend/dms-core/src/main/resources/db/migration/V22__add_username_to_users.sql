ALTER TABLE users ADD COLUMN username VARCHAR(100);

-- Backfill existing rows from the email local-part (the same value the
-- frontend was deriving client-side), deduplicated within each tenant so
-- the NOT NULL + unique constraint below can never fail on legacy data.
WITH candidates AS (
    SELECT user_id,
           tenant_id,
           split_part(email, '@', 1) AS base_username,
           row_number() OVER (
               PARTITION BY tenant_id, split_part(email, '@', 1)
               ORDER BY created_at
           ) AS rn
    FROM users
)
UPDATE users u
SET username = CASE
    WHEN c.rn = 1 THEN c.base_username
    ELSE c.base_username || '-' || substr(u.user_id::text, 1, 8)
END
FROM candidates c
WHERE u.user_id = c.user_id;

ALTER TABLE users ALTER COLUMN username SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT uq_users_tenant_username UNIQUE (tenant_id, username);
