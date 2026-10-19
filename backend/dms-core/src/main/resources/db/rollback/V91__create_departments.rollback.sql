-- Manual rollback ("down") for V91__create_departments.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it (see
-- V41__create_export_jobs.rollback.sql). Run V92/V93 rollbacks first -
-- nothing in V91 can be dropped while workflow tables still exist.
ALTER TABLE users DROP CONSTRAINT IF EXISTS fk_users_department;
DROP INDEX IF EXISTS ix_users_department;
ALTER TABLE users DROP COLUMN IF EXISTS department_id;
DROP TABLE IF EXISTS departments;
