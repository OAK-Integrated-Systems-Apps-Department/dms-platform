-- Module 2.1 - File Import/Export: let a user who started an import or an
-- export be hard-deleted (UserServiceImpl#purgeUser).
--
-- V41/V42 declared created_by/updated_by on import_batches and export_jobs
-- as bare REFERENCES users with no ON DELETE behavior, so Postgres rejected
-- deleting anyone who had ever run an import or export. Following V14:
-- these columns are an audit trail on rows that have nothing to do with the
-- delete, so ON DELETE SET NULL. All four columns are already nullable.
--
-- A batch whose creator is gone is still finished by the import worker,
-- completion job and recovery job: rows not yet handed to the scan gate
-- fail with CREATOR_DELETED and the batch is finalised (see
-- ImportBatchFinaliser#finaliseWithoutCreator) - nothing ever runs as a
-- null user.
--
-- import_items has no user columns. Constraint names are Postgres's own
-- default (<table>_<column>_fkey) - V41/V42 declared them inline.

ALTER TABLE import_batches DROP CONSTRAINT import_batches_created_by_fkey;
ALTER TABLE import_batches ADD CONSTRAINT import_batches_created_by_fkey FOREIGN KEY (created_by)
        REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE import_batches DROP CONSTRAINT import_batches_updated_by_fkey;
ALTER TABLE import_batches ADD CONSTRAINT import_batches_updated_by_fkey FOREIGN KEY (updated_by)
        REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE export_jobs DROP CONSTRAINT export_jobs_created_by_fkey;
ALTER TABLE export_jobs ADD CONSTRAINT export_jobs_created_by_fkey FOREIGN KEY (created_by)
        REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE export_jobs DROP CONSTRAINT export_jobs_updated_by_fkey;
ALTER TABLE export_jobs ADD CONSTRAINT export_jobs_updated_by_fkey FOREIGN KEY (updated_by)
        REFERENCES users (user_id) ON DELETE SET NULL;
