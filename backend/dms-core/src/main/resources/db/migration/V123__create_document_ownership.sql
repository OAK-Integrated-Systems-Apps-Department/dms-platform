-- Document Ownership and Transfer (DOT): owner department + state on documents, a holding owner on
-- departments, transfer batches and the append-only ownership history. Additive only.

-- 1. documents: owner department + state
ALTER TABLE documents
    ADD COLUMN owner_department_id UUID NULL,
    ADD COLUMN ownership_state VARCHAR(10) NOT NULL DEFAULT 'assigned',
    ADD CONSTRAINT ck_documents_ownership_state CHECK (ownership_state IN ('assigned','holding')),
    ADD CONSTRAINT fk_documents_owner_department FOREIGN KEY (tenant_id, owner_department_id)
        REFERENCES departments (tenant_id, department_id);          -- MATCH SIMPLE: NULL skips the check
CREATE INDEX ix_documents_tenant_owner ON documents (tenant_id, owner_user_id) WHERE purged_at IS NULL;
CREATE INDEX ix_documents_owner_department ON documents (tenant_id, owner_department_id)
    WHERE owner_department_id IS NOT NULL AND purged_at IS NULL;
CREATE INDEX ix_documents_holding ON documents (tenant_id, owner_department_id)
    WHERE ownership_state = 'holding' AND purged_at IS NULL;

-- 2. departments: holding owner
ALTER TABLE departments
    ADD COLUMN holding_owner_user_id UUID NULL REFERENCES users (user_id) ON DELETE SET NULL;

-- 3. batches (inserted once, at the end of the transaction)
CREATE TABLE ownership_transfer_batches (
    batch_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    kind VARCHAR(16) NOT NULL,
    source_user_id UUID NULL,                 -- plain ids: the user may be purged later
    source_department_id UUID NULL,
    new_owner_user_id UUID NOT NULL,
    new_owner_department_id UUID NULL,
    reason VARCHAR(16) NOT NULL,
    total_count INT NOT NULL,
    transferred_count INT NOT NULL,
    skipped_count INT NOT NULL,
    skipped_summary JSONB NOT NULL DEFAULT '{}'::jsonb,   -- {"already_owner":3,"purged":1,...}
    access_granted_count INT NOT NULL DEFAULT 0,
    converted_to_restricted_count INT NOT NULL DEFAULT 0,   -- documents switched to Restricted so the new owner could open them (D4)
    flagged_task_count INT NOT NULL DEFAULT 0,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ NULL,
    note VARCHAR(1000) NULL,
    CONSTRAINT uq_ownership_batches_tenant_batch UNIQUE (tenant_id, batch_id),
    CONSTRAINT ck_ownership_batches_kind CHECK (kind IN ('single','bulk','offboarding','reorganization')),
    CONSTRAINT ck_ownership_batches_reason CHECK (reason IN ('manual','offboarding','reorganization','holding')),
    CONSTRAINT ck_ownership_batches_counts CHECK (transferred_count + skipped_count = total_count)
);
CREATE INDEX ix_ownership_batches_tenant_created ON ownership_transfer_batches (tenant_id, created_at DESC);

-- 4. history (append-only)
CREATE TABLE ownership_transfers (
    transfer_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    document_id UUID NOT NULL,
    previous_owner_user_id UUID NOT NULL,
    previous_owner_department_id UUID NULL,
    new_owner_user_id UUID NOT NULL,
    new_owner_department_id UUID NULL,
    reason VARCHAR(16) NOT NULL,
    batch_id UUID NULL,
    actor_user_id UUID NOT NULL,
    access_granted BOOLEAN NOT NULL DEFAULT false,
    note VARCHAR(1000) NULL,
    previous_owner_name VARCHAR(200) NULL,     -- snapshots so history survives a purged user (Q2)
    new_owner_name VARCHAR(200) NULL,
    actor_name VARCHAR(200) NULL,
    transferred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_ownership_transfers_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES documents (tenant_id, document_id),
    CONSTRAINT fk_ownership_transfers_batch FOREIGN KEY (tenant_id, batch_id)
        REFERENCES ownership_transfer_batches (tenant_id, batch_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_ownership_transfers_reason CHECK (reason IN ('manual','offboarding','reorganization','holding'))
);
CREATE INDEX ix_ownership_transfers_document ON ownership_transfers (tenant_id, document_id, transferred_at DESC);
CREATE INDEX ix_ownership_transfers_batch ON ownership_transfers (tenant_id, batch_id);
CREATE INDEX ix_ownership_transfers_time ON ownership_transfers (tenant_id, transferred_at DESC);
CREATE INDEX ix_ownership_transfers_prev ON ownership_transfers (tenant_id, previous_owner_user_id);
CREATE INDEX ix_ownership_transfers_new ON ownership_transfers (tenant_id, new_owner_user_id);

CREATE FUNCTION ownership_transfers_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ownership_transfers is append-only (% blocked)', TG_OP;
END; $$;
CREATE TRIGGER trg_ownership_transfers_immutable BEFORE UPDATE OR DELETE ON ownership_transfers
    FOR EACH ROW EXECUTE FUNCTION ownership_transfers_immutable();
REVOKE UPDATE, DELETE ON ownership_transfers FROM PUBLIC;
