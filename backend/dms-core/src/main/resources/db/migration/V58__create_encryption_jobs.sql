-- Encryption key-management jobs (re-wrap, encrypt existing files) - one row
-- per tenant and job, shared by every dms-core instance.
--
-- Claiming a job is a single conditional upsert, so two instances (or two
-- clicks) can never run the same job for the same tenant at once. A running
-- job writes a heartbeat; one whose heartbeat stops (its instance died) can be
-- claimed again after a timeout, and the screen shows it as interrupted.
-- Progress survives restarts because it lives here, not in memory.

CREATE TABLE encryption_jobs (
    tenant_id     UUID         NOT NULL REFERENCES tenants (tenant_id),
    job           VARCHAR(30)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    processed     BIGINT       NOT NULL DEFAULT 0,
    failed        BIGINT       NOT NULL DEFAULT 0,
    started_at    TIMESTAMPTZ  NOT NULL,
    finished_at   TIMESTAMPTZ,
    heartbeat_at  TIMESTAMPTZ  NOT NULL,
    started_by    UUID,
    -- Which instance runs it (host name), for diagnosing a stuck job.
    instance      VARCHAR(200),
    last_error    VARCHAR(1000),
    PRIMARY KEY (tenant_id, job),
    CONSTRAINT ck_encryption_jobs_job CHECK (job IN ('rewrap', 'encrypt-existing')),
    CONSTRAINT ck_encryption_jobs_status CHECK (status IN ('running', 'finished', 'failed'))
);
