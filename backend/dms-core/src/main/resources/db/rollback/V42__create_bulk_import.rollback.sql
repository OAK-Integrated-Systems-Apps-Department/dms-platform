-- Manual rollback ("down") for V42__create_bulk_import.sql - see
-- V41__create_export_jobs.rollback.sql for why this lives outside
-- classpath:db/migration. Children first (migration_items references
-- migration_batches); dropping a table drops its indexes and constraints.
DROP TABLE IF EXISTS upload_sessions;
DROP TABLE IF EXISTS migration_items;
DROP TABLE IF EXISTS migration_batches;
