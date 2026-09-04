-- Adds the missing activate/deactivate field to `roles`. The Role List
-- (R1) and Add/Edit Role (R2) screens have always shown an Active/Inactive
-- toggle, and RoleServiceImpl's soft-delete already tracks `is_deleted`
-- for actual deletion — but nothing tracked the "temporarily disabled,
-- still exists" state those screens display, so it was mocked
-- client-side only.
--
-- Mirrors `users.status` (see V1__init_core_schema.sql): a plain
-- lower-case VARCHAR with a CHECK constraint rather than a Postgres ENUM
-- type, so adding a future status value later is just a constraint change,
-- not a type migration. Two values only, not three — roles don't have a
-- "locked" equivalent (that's a per-account login concept, not a
-- per-role-definition one).
ALTER TABLE roles
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'active';

ALTER TABLE roles
    ADD CONSTRAINT ck_roles_status CHECK (status IN ('active', 'inactive'));
