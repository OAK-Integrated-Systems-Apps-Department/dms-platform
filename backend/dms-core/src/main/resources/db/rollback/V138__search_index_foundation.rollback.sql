-- Manual rollback ("down") for V138__search_index_foundation.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Triggers first (they call the functions), then the
-- functions, then the tables and the sequence. The OpenSearch index is derived data and is simply left to be
-- dropped or rebuilt; nothing here touches documents.
DROP TRIGGER IF EXISTS trg_search_tenants_upd        ON tenants;
DROP TRIGGER IF EXISTS trg_search_folders_upd        ON folders;
DROP TRIGGER IF EXISTS trg_search_renditions_upd     ON renditions;
DROP TRIGGER IF EXISTS trg_search_renditions_ins     ON renditions;
DROP TRIGGER IF EXISTS trg_search_ocr_outputs_upd    ON ocr_outputs;
DROP TRIGGER IF EXISTS trg_search_ocr_outputs_ins    ON ocr_outputs;
DROP TRIGGER IF EXISTS trg_search_versions_upd       ON document_versions;
DROP TRIGGER IF EXISTS trg_search_versions_ins       ON document_versions;
DROP TRIGGER IF EXISTS trg_search_doc_metadata_upd   ON document_metadata;
DROP TRIGGER IF EXISTS trg_search_doc_metadata_ins   ON document_metadata;
DROP TRIGGER IF EXISTS trg_search_documents_upd      ON documents;
DROP TRIGGER IF EXISTS trg_search_documents_ins      ON documents;

DROP FUNCTION IF EXISTS search_trg_tenants();
DROP FUNCTION IF EXISTS search_trg_folders();
DROP FUNCTION IF EXISTS search_trg_version_artifact();
DROP FUNCTION IF EXISTS search_trg_document_versions();
DROP FUNCTION IF EXISTS search_trg_document_metadata();
DROP FUNCTION IF EXISTS search_trg_documents();
DROP FUNCTION IF EXISTS search_enqueue_document(UUID, UUID, TEXT);

DROP TABLE IF EXISTS search_index_jobs;
DROP TABLE IF EXISTS search_tenant_state;
DROP SEQUENCE IF EXISTS search_change_seq;
-- Also: DELETE FROM flyway_schema_history WHERE version = '138';
