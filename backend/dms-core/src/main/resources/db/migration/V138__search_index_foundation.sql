-- Document Search & Retrieval (DSR): the index feed.
--
-- OpenSearch is derived data; PostgreSQL stays the source of truth. This migration adds the durable queue
-- that feeds the indexer and the per-tenant state, and - the important part - database triggers that enqueue
-- an index job whenever something that appears in the index changes. Triggers instead of per-service hooks
-- because Recycle Bin, restore, purge, lifecycle, legal hold and move emit no events today, and bulk import
-- writes straight through; a trigger covers every write path without touching any of them.
--
-- One pending row per document (and per folder/tenant): repeated changes coalesce by bumping change_counter,
-- which the indexer sends to OpenSearch as the external version, so a late job can never overwrite newer data.
-- Visibility (folder/document access, workflow-readable) is deliberately NOT indexed: it is verified in
-- PostgreSQL per query, so access changes need no reindex.

CREATE SEQUENCE IF NOT EXISTS search_change_seq;

CREATE TABLE search_tenant_state (
    tenant_id          UUID PRIMARY KEY REFERENCES tenants (tenant_id),
    enabled            BOOLEAN     NOT NULL DEFAULT TRUE,   -- per-tenant switch: false = title-search fallback
    index_ready        BOOLEAN     NOT NULL DEFAULT FALSE,
    generation         BIGINT      NOT NULL DEFAULT 1,      -- bumped by a tenant rebuild
    backfill_state     TEXT        NOT NULL DEFAULT 'none',
    backfill_cursor    UUID,
    last_backfill_at   TIMESTAMPTZ,
    last_reconciled_at TIMESTAMPTZ,
    last_gap           JSONB,                               -- {"missing":n,"orphans":n}
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_search_tenant_state_backfill CHECK (backfill_state IN ('none', 'running', 'done', 'failed'))
);

CREATE TABLE search_index_jobs (
    job_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL,
    kind            TEXT        NOT NULL,
    document_id     UUID,
    folder_id       UUID,
    reason          TEXT        NOT NULL,
    change_counter  BIGINT      NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'pending',
    attempts        INT         NOT NULL DEFAULT 0,
    max_attempts    INT         NOT NULL DEFAULT 8,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until    TIMESTAMPTZ,
    last_error      TEXT,
    enqueued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_search_jobs_kind   CHECK (kind IN ('document', 'folder_subtree', 'tenant_purge')),
    CONSTRAINT ck_search_jobs_status CHECK (status IN ('pending', 'failed')),
    CONSTRAINT ck_search_jobs_target CHECK (
        (kind = 'document' AND document_id IS NOT NULL)
        OR (kind = 'folder_subtree' AND folder_id IS NOT NULL)
        OR (kind = 'tenant_purge'))
);

CREATE UNIQUE INDEX ux_search_jobs_document ON search_index_jobs (tenant_id, document_id) WHERE kind = 'document';
CREATE UNIQUE INDEX ux_search_jobs_folder   ON search_index_jobs (tenant_id, folder_id)   WHERE kind = 'folder_subtree';
CREATE UNIQUE INDEX ux_search_jobs_tenant   ON search_index_jobs (tenant_id)              WHERE kind = 'tenant_purge';
-- The claim query: due pending rows in order.
CREATE INDEX ix_search_jobs_claim  ON search_index_jobs (next_attempt_at) WHERE status = 'pending';
-- The admin status page and the lag gauge.
CREATE INDEX ix_search_jobs_failed ON search_index_jobs (tenant_id) WHERE status = 'failed';

-- Enqueue (or coalesce into) the single pending job for a document. A newer change resets attempts so a job that
-- had given up (status 'failed') is retried by the next real change.
CREATE FUNCTION search_enqueue_document(p_tenant UUID, p_document UUID, p_reason TEXT) RETURNS void
LANGUAGE sql AS $$
    INSERT INTO search_index_jobs (tenant_id, kind, document_id, reason, change_counter)
    VALUES (p_tenant, 'document', p_document, p_reason, nextval('search_change_seq'))
    ON CONFLICT (tenant_id, document_id) WHERE kind = 'document'
    DO UPDATE SET change_counter = EXCLUDED.change_counter,
                  reason         = EXCLUDED.reason,
                  status         = 'pending',
                  attempts       = 0,
                  next_attempt_at = now(),
                  last_error     = NULL,
                  updated_at     = now()
$$;

-- documents ---------------------------------------------------------------------------------------------------
CREATE FUNCTION search_trg_documents() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    r TEXT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        r := 'created';
    ELSIF NEW.purged_at IS DISTINCT FROM OLD.purged_at THEN
        r := 'purge';
    ELSIF NEW.is_deleted IS DISTINCT FROM OLD.is_deleted THEN
        r := CASE WHEN NEW.is_deleted THEN 'delete' ELSE 'restore' END;
    ELSIF NEW.lifecycle_state IS DISTINCT FROM OLD.lifecycle_state THEN
        r := 'lifecycle';
    ELSIF NEW.legal_hold IS DISTINCT FROM OLD.legal_hold THEN
        r := 'hold';
    ELSIF NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id
       OR NEW.owner_department_id IS DISTINCT FROM OLD.owner_department_id THEN
        r := 'owner';
    ELSIF NEW.folder_id IS DISTINCT FROM OLD.folder_id THEN
        r := 'move';
    ELSIF NEW.title IS DISTINCT FROM OLD.title THEN
        r := 'title';
    ELSIF NEW.document_type_id IS DISTINCT FROM OLD.document_type_id THEN
        r := 'type';
    ELSIF NEW.is_template IS DISTINCT FROM OLD.is_template THEN
        r := 'template';
    ELSIF NEW.current_version_id IS DISTINCT FROM OLD.current_version_id
       OR NEW.latest_version_id IS DISTINCT FROM OLD.latest_version_id THEN
        r := 'new_version';
    ELSE
        r := 'modified';
    END IF;
    PERFORM search_enqueue_document(NEW.tenant_id, NEW.document_id, r);
    RETURN NULL;
END $$;

CREATE TRIGGER trg_search_documents_ins AFTER INSERT ON documents
    FOR EACH ROW EXECUTE FUNCTION search_trg_documents();
-- The WHEN clause keeps unrelated updates (check-out bookkeeping, signature lock, ...) free.
CREATE TRIGGER trg_search_documents_upd AFTER UPDATE ON documents
    FOR EACH ROW
    WHEN (OLD.title IS DISTINCT FROM NEW.title
       OR OLD.folder_id IS DISTINCT FROM NEW.folder_id
       OR OLD.owner_user_id IS DISTINCT FROM NEW.owner_user_id
       OR OLD.owner_department_id IS DISTINCT FROM NEW.owner_department_id
       OR OLD.document_type_id IS DISTINCT FROM NEW.document_type_id
       OR OLD.lifecycle_state IS DISTINCT FROM NEW.lifecycle_state
       OR OLD.legal_hold IS DISTINCT FROM NEW.legal_hold
       OR OLD.is_deleted IS DISTINCT FROM NEW.is_deleted
       OR OLD.purged_at IS DISTINCT FROM NEW.purged_at
       OR OLD.is_template IS DISTINCT FROM NEW.is_template
       OR OLD.current_version_id IS DISTINCT FROM NEW.current_version_id
       OR OLD.latest_version_id IS DISTINCT FROM NEW.latest_version_id
       OR OLD.updated_at IS DISTINCT FROM NEW.updated_at)
    EXECUTE FUNCTION search_trg_documents();

-- document_metadata -------------------------------------------------------------------------------------------
CREATE FUNCTION search_trg_document_metadata() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM search_enqueue_document(NEW.tenant_id, NEW.document_id, 'metadata');
    RETURN NULL;
END $$;

CREATE TRIGGER trg_search_doc_metadata_ins AFTER INSERT ON document_metadata
    FOR EACH ROW EXECUTE FUNCTION search_trg_document_metadata();
CREATE TRIGGER trg_search_doc_metadata_upd AFTER UPDATE ON document_metadata
    FOR EACH ROW
    WHEN (OLD.field_values IS DISTINCT FROM NEW.field_values OR OLD.document_type_id IS DISTINCT FROM NEW.document_type_id)
    EXECUTE FUNCTION search_trg_document_metadata();

-- document_versions -------------------------------------------------------------------------------------------
CREATE FUNCTION search_trg_document_versions() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    r TEXT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        r := 'version';
    ELSIF NEW.ocr_status IS DISTINCT FROM OLD.ocr_status THEN
        r := 'ocr';
    ELSIF NEW.rendition_status IS DISTINCT FROM OLD.rendition_status
       OR NEW.rendition_object_key IS DISTINCT FROM OLD.rendition_object_key THEN
        r := 'rendition';
    ELSE
        r := 'version';
    END IF;
    PERFORM search_enqueue_document(NEW.tenant_id, NEW.document_id, r);
    RETURN NULL;
END $$;

CREATE TRIGGER trg_search_versions_ins AFTER INSERT ON document_versions
    FOR EACH ROW EXECUTE FUNCTION search_trg_document_versions();
CREATE TRIGGER trg_search_versions_upd AFTER UPDATE ON document_versions
    FOR EACH ROW
    WHEN (OLD.ocr_status IS DISTINCT FROM NEW.ocr_status
       OR OLD.rendition_status IS DISTINCT FROM NEW.rendition_status
       OR OLD.scan_status IS DISTINCT FROM NEW.scan_status
       OR OLD.purged_at IS DISTINCT FROM NEW.purged_at
       OR OLD.is_deleted IS DISTINCT FROM NEW.is_deleted
       OR OLD.rendition_object_key IS DISTINCT FROM NEW.rendition_object_key
       OR OLD.original_filename IS DISTINCT FROM NEW.original_filename
       OR OLD.mime_type IS DISTINCT FROM NEW.mime_type
       OR OLD.size_bytes IS DISTINCT FROM NEW.size_bytes)
    EXECUTE FUNCTION search_trg_document_versions();

-- ocr_outputs and renditions (keyed by version; the document is one lookup away) -------------------------------
CREATE FUNCTION search_trg_version_artifact() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    d UUID;
BEGIN
    SELECT document_id INTO d FROM document_versions WHERE version_id = NEW.version_id;
    IF d IS NOT NULL THEN
        PERFORM search_enqueue_document(NEW.tenant_id, d, TG_ARGV[0]);
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER trg_search_ocr_outputs_ins AFTER INSERT ON ocr_outputs
    FOR EACH ROW EXECUTE FUNCTION search_trg_version_artifact('ocr');
CREATE TRIGGER trg_search_ocr_outputs_upd AFTER UPDATE ON ocr_outputs
    FOR EACH ROW
    WHEN (OLD.status IS DISTINCT FROM NEW.status OR OLD.extracted_text_ref IS DISTINCT FROM NEW.extracted_text_ref)
    EXECUTE FUNCTION search_trg_version_artifact('ocr');

CREATE TRIGGER trg_search_renditions_ins AFTER INSERT ON renditions
    FOR EACH ROW EXECUTE FUNCTION search_trg_version_artifact('rendition');
CREATE TRIGGER trg_search_renditions_upd AFTER UPDATE ON renditions
    FOR EACH ROW
    WHEN (OLD.status IS DISTINCT FROM NEW.status OR OLD.storage_object_key IS DISTINCT FROM NEW.storage_object_key)
    EXECUTE FUNCTION search_trg_version_artifact('rendition');

-- folders: a move or delete changes the ancestor ids of every document below it. One cheap subtree job; the
-- indexer expands it to document jobs in pages (no long-running trigger). ------------------------------------
CREATE FUNCTION search_trg_folders() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO search_index_jobs (tenant_id, kind, folder_id, reason, change_counter)
    VALUES (NEW.tenant_id, 'folder_subtree', NEW.folder_id, 'folder', nextval('search_change_seq'))
    ON CONFLICT (tenant_id, folder_id) WHERE kind = 'folder_subtree'
    DO UPDATE SET change_counter = EXCLUDED.change_counter, status = 'pending', attempts = 0,
                  next_attempt_at = now(), last_error = NULL, updated_at = now();
    RETURN NULL;
END $$;

CREATE TRIGGER trg_search_folders_upd AFTER UPDATE ON folders
    FOR EACH ROW
    WHEN (OLD.parent_folder_id IS DISTINCT FROM NEW.parent_folder_id OR OLD.is_deleted IS DISTINCT FROM NEW.is_deleted)
    EXECUTE FUNCTION search_trg_folders();

-- tenants: removing a tenant removes its documents from the index. ----------------------------------------------
CREATE FUNCTION search_trg_tenants() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO search_index_jobs (tenant_id, kind, reason, change_counter)
    VALUES (NEW.tenant_id, 'tenant_purge', 'tenant_removed', nextval('search_change_seq'))
    ON CONFLICT (tenant_id) WHERE kind = 'tenant_purge'
    DO UPDATE SET change_counter = EXCLUDED.change_counter, status = 'pending', attempts = 0,
                  next_attempt_at = now(), last_error = NULL, updated_at = now();
    RETURN NULL;
END $$;

CREATE TRIGGER trg_search_tenants_upd AFTER UPDATE ON tenants
    FOR EACH ROW
    WHEN (NEW.is_deleted AND NOT OLD.is_deleted)
    EXECUTE FUNCTION search_trg_tenants();
