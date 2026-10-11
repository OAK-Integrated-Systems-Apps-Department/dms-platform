-- Module 2.1 - File Import/Export: "my exports" lists a user's export jobs
-- newest first, and requesting an export counts that user's pending and
-- processing jobs (one active export per user by default). Both look up
-- export_jobs by (tenant_id, created_by); V42's indexes are on
-- (tenant_id, status) and expires_at only.

CREATE INDEX ix_export_jobs_tenant_created_by
    ON export_jobs (tenant_id, created_by, created_at DESC);
