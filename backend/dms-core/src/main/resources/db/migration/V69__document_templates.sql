-- Document templates: ready-made files (a blank contract in Word, a proposal in PowerPoint, a
-- form as PDF) that admins add to a document type, for people to download or start a new
-- document from. A template is stored as a document - virus-scanned, encrypted, versioned and
-- previewed like any other - flagged here and kept out of every document list, the recycle bin,
-- duplicate checks and downloads of folders. It changes only through the template screens:
-- everywhere else it's view-only (DocumentAccessService).
alter table documents
    add column is_template boolean not null default false;

create index ix_documents_templates
    on documents (tenant_id, document_type_id)
    where is_template and not is_deleted;

-- 'template': the version a template was uploaded with (later replacements are 'replacement_upload').
alter table document_versions drop constraint ck_document_versions_created_reason;
alter table document_versions add constraint ck_document_versions_created_reason check (created_reason in
    ('initial_upload', 'replacement_upload', 'workflow_update', 'signed_copy', 'rollback', 'migration', 'template'));

alter table scan_results drop constraint ck_scan_results_created_reason;
alter table scan_results add constraint ck_scan_results_created_reason check (created_reason in
    ('initial_upload', 'replacement_upload', 'workflow_update', 'signed_copy', 'rollback', 'migration', 'template'));
