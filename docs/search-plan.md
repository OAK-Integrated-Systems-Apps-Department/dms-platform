# Oak DMS: Document Search & Retrieval (DSR) with OpenSearch
Plan v1, 2026-11-10. Branch `feature/chamath/search-and-retrieval` at V137. Nothing has been built.
(Saved here because plan mode only allows this file. On approval it is copied to `docs/search-plan.md`, which is the single source of truth, and gets the "Progress log" and "Deviations" tables.)

## 0. Context
Users can only search by title today (`GET /api/v1/documents?q=`, a `lower(title) LIKE` match). Phase 1 adds real search: keywords, phrases, metadata, full text, ranking, highlights, filters, and strict per-user visibility. OpenSearch is derived data and PostgreSQL stays the source of truth (D1). Phase 2 (presets, preview integration, monitoring, production hardening) is outlined in section 13.

## 0.1 Decisions recorded from the user (authoritative; override anything below that disagrees)
- **O1** Build with the local setup (security plugin off, internal network only). The production controls and the security owner's sign-off are required **before S15 and before any production use**.
- **O2** Yes: use the encrypted extracted-text cache artefact.
- **O3** Accepted: per-tenant rebuild = generation sweep; alias swap only for a global rebuild.
- **O4** Admin endpoints: tenant admin only, reusing the existing check.
- **O5** Yes: V139 grants `dsr.search-documents` to roles that hold `docm.documents.view`, as planned.
- **C1 (test support)** `SearchIntegrationTestSupport` makes OpenSearch-dependent ITs **skip (JUnit assumption)** when the test OpenSearch is unreachable, and **fail loudly only when `OPENSEARCH_IT_REQUIRED=true`**. I run locally with that flag set. Adding an OpenSearch container to CI stays a documented follow-up (no workflow edits).
- **C2 (reconciler)** Add a lightweight daily per-tenant reconciler `SearchReconciler`: compares the number of eligible documents in PostgreSQL (not deleted, not purged, not template, and the same lifecycle set as the index) with the index count for that tenant, enqueues the missing ones (reason `reconcile`, via `search_enqueue_document`), and records the gap in `search_tenant_state` (new columns `last_reconciled_at`, `last_gap`) so `GET /api/v1/admin/search/status` reports it. Runs under a Postgres advisory lock (like `RetentionJob`) so only one instance runs it; config `dms.search.reconcile.{enabled, cron}`. Detects missing documents; orphans (in the index but no longer eligible) are also counted and enqueued as deletes by listing ids only when the counts disagree. No document text is touched or logged.
- **C3 (trigger tests)** Every trigger gets a test proving the business write **succeeds** and **exactly one** job exists afterwards (including coalescing of repeated changes). S10 measures and reports the trigger overhead on a **5,000-document bulk update** (for example an ownership transfer), with and without triggers.
- Git: I never run any git command that changes anything.

## 1. Findings: verified, and where the brief is wrong

### Confirmed
- `com/oak/dms/search/` holds only `.gitkeep`. There is no OpenSearch, Elasticsearch or Lucene anywhere in pom, yml, compose or code. `infra/search/` and `backend/workers/indexing-worker/` are also `.gitkeep` placeholders.
- Title search is `DocumentServiceImpl.listDocumentsIn` (l.267-303) over `DocumentRepository` `LIVE_TITLED`/`NARROWED` (+ `FOLLOWS_FOLDER | ON_ITS_LIST | IN_MY_WORKFLOW`). It excludes `is_deleted` and templates and keeps `lifecycle_state in :states`. `DocumentView.ACTIVE` = `['active']` and `ARCHIVE` = `['inactive','archived']`, the latter needing `crm.view-archive`. V100 has the trigram index. Frontend: `topbar-search.tsx` (fetchDocuments, size 6, then "Browse My Documents"). No `/search` route, and `nav-config.ts` says Search has no page yet.
- Permissions are seeded in V6/V7/V10: module `DSR`; `dsr.search-documents`; `dsr.manage-saved-search-presets.{create,update,delete,share}`. `crm.view-archive` is from V130. No constants exist in Java `PermissionCodes` or `permission-codes.ts` (both say "add only once a check references it").
- OCR text: `ocr_outputs.extracted_text_ref` is the key `ocr/{tenant}/{doc}/{version}/ocr.json`; the searchable PDF is `.../searchable.pdf` with a `renditions` row of type `searchable_pdf`. `ocr.json` has a top-level `"text"` (all pages joined) and a `pages[]` array (ocr-worker README l.486). No Java code reads it back today.
- Reads decrypt transparently: `ObjectStorageServiceFactory.resolve(location).download(...)` returns the `EncryptingObjectStorageService` wrapper. Location comes from `StorageLocationResolver.resolveForTenant(tenantId, StoragePurpose.document)`.
- PDFBox 3.0.3 is on the classpath, plus POI 5.5.1 and `tika-core` 3.2.2 (MIME detection only, no parsers). `DocumentFingerprints`/`DuplicateFinder` already extract text (25 MB cap): a pattern to reuse.
- Visibility: `DocumentAccessService.forCaller()/forUsers()`, `DocumentAccessView.canSee()`, and the existing set-based helper `DocumentServiceImpl.visibleItems(ids)` (l.322-335). `OwnedDocumentsSql.visibleTo` has the SQL shape.
- The queue pattern is `notification/dispatch/DeliveryClaimRepository` (`UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED LIMIT ?) RETURNING`), with `NotificationDispatcher` backoff and `NotificationProperties.Dispatcher`. `OutboxRelay` has no SKIP LOCKED, so it is not multi-instance safe. Confirmed.
- Tagging package is empty. Highest Flyway number on every local and remote ref is **V137**, so DSR starts at **V138**. Disk is 18 GiB free (limit 15).

### Corrections to the brief
1. `is_searchable` was **not** dropped. V31 dropped `is_indexed` and explicitly kept `is_searchable` (default true). Phase 1 uses it to decide which fields feed `metadata_text`.
2. `RenditionGenerator` does not exist. Generation is `DocumentRenditionService.generate()` (l.460) via `RenditionGenerationListener`. `PreviewController` is only policy and failures admin.
3. **There is no document rename or title-edit path.** Only `FolderService.rename/move` exist. The index hook for "rename" is ready (trigger on `documents.title`) but nothing fires it today.
4. `documents` has **no change counter** and no `@Version`. The lifecycle column is JPA read-only (updated by a query). Recycle Bin, restore, purge, lifecycle, legal hold and move emit **no Spring event** at all. This is why section 7 uses database triggers instead of per-service hooks.
5. `document_metadata.field_values` is JSONB keyed by **fieldId (UUID)** with `{v, src, conf}`; there is no per-type value table. Field types: `text, long_text, number, decimal, date, datetime, boolean, dropdown, multi_select, user_ref, lookup`.
6. Jackson is mixed. Jackson 3 (`tools.jackson`) is used in 53 files and Jackson 2 in 62. Spring Boot is **4.1.0**; `java.version` is **17** (a pom comment says 21).
7. No Testcontainers dependency exists. Integration tests use a real Postgres on **5433 by default** (CI uses service containers), and port 5435 is referenced nowhere. So `DB_URL/DB_USERNAME/DB_PASSWORD` must be exported for every run.
8. The ICU analysis plugin ships Unicode word-break dictionaries, normalisation and collation data, **not fonts**. Fonts matter only for rendering (watermark/preview). ICU is still worth it: the OCR target market is Sri Lanka (Sinhala, Tamil) and word-break/folding there is Unicode-specific. The product has no i18n library and is effectively English-only in the UI.
9. Tenant filtering is purely application-level (no Postgres RLS). Cross-tenant ids already return 404 via `ResourceNotFoundException`.
10. "Tenant rebuild with alias swap" conflicts with D2 as written. One shared index cannot be swapped for one tenant. Resolution in section 8 (decision O3).

## 2. Locked decisions
D1-D13 apply unchanged. Additional design decisions made under them (all listed in section 14 for sign-off):
- **Triggers** feed the queue (every write path covered, no service edits, browse untouched).
- **Verification in PostgreSQL reuses `DocumentAccessService` + the `visibleItems` pattern in memory** (one code path, zero drift from browse), not a second SQL predicate.
- **Deleted and purged documents are removed from the index** (delete op); the `is_deleted/purged` fields remain as a defence-in-depth filter.
- **Highlights are fetched in a second request for verified ids only.**
- **"Did you mean" is computed in Java from the user's own verified results**, so no suggester index exists that could hold hidden terms.
- **Indexer runs inside dms-core** (needs DB and the decrypt path), not in the placeholder `indexing-worker`.

## 3. OpenSearch client: recommendation
**Spring `RestClient` (already on the classpath through `spring-boot-starter-restclient`) + Jackson 3 `JsonNode`, behind `SearchEngine`. Zero new dependencies.**

| Option | New deps (licence) | Notes |
|---|---|---|
| A. `opensearch-java` 3.x + `ApacheHttpClient5Transport` | opensearch-java (Apache-2.0), httpclient5 + httpcore5 + h2 (Apache-2.0), Jakarta JSON-P API + Parsson (EPL-2.0 / GPL-2.0+CPE), jackson-databind 2 (Apache-2.0) | Its JSON mapper is Jackson **2** (`com.fasterxml`). Web evidence says Jackson 3 support is only in a future 4.x (unverified). Adds a second JSON stack beside Spring Boot 4's Jackson 3. Typed DSL is nice but we use about 10 endpoints. |
| **B. RestClient (recommended)** | none | Bulk NDJSON, `_search`, `_aliases`, `_bulk`, `_delete_by_query`, `_cluster/health` are small. All JSON via the Jackson 3 mapper already in the app. Full control over timeouts and circuit breaker. Cost: hand-built query JSON (kept in one `SearchQueryBuilder`, tested against a real node). |

Evidence caveat: I could not confirm `httpclient5` is already transitive (`mvn dependency:tree` needs approval); option B does not depend on it. Re-evaluate only if hand-built queries become a maintenance burden.

**Image:** pin `opensearchproject/opensearch:3.7.0` (web search shows 3.7.0 as latest stable; a matching `analysis-icu` 3.7.0 plugin exists; **the exact tag must be verified with `docker manifest inspect` in S2 before pinning**). Custom image `infra/local/opensearch/Dockerfile`: `FROM opensearchproject/opensearch:${OS_VERSION}` + `RUN bin/opensearch-plugin install --batch analysis-icu`, built only with `docker compose build opensearch`.

## 4. Platform (S2)
Compose service (`infra/local/docker-compose.yml`):
```yaml
opensearch:
  build: { context: ./opensearch, args: { OS_VERSION: "3.7.0" } }   # verify tag in S2
  image: dms-opensearch:3.7.0-icu
  mem_limit: ${OPENSEARCH_MEM_LIMIT:-1536m}
  environment:
    discovery.type: single-node
    DISABLE_INSTALL_DEMO_CONFIG: "true"
    DISABLE_SECURITY_PLUGIN: "true"   # DEV ONLY: reachable solely on the internal compose network, no published port. Production sets auth+TLS (Phase 2 S15).
    OPENSEARCH_JAVA_OPTS: "-Xms512m -Xmx512m"
    bootstrap.memory_lock: "false"
  ulimits: { nofile: { soft: 65536, hard: 65536 } }
  volumes: [ opensearch_data:/usr/share/opensearch/data ]
  healthcheck: { test: ["CMD-SHELL","curl -fs 'http://localhost:9200/_cluster/health?wait_for_status=yellow&timeout=5s' >/dev/null"], interval: 10s, timeout: 8s, retries: 12, start_period: 40s }
```
- No `ports:`. dms-core gets `OPENSEARCH_URL=${OPENSEARCH_URL:-http://opensearch:9200}` with **no `depends_on`** (search must degrade, never block startup). New named volume `opensearch_data`.
- Memory note: dms-core 2560m + OpenSearch 1536m on top of the existing limits (Docker VM ~7.8 GB, the stack is not run all at once). I will report, not fix, if memory looks too small (rule 9).
- Secrets: the cursor-signing key goes in `infra/secrets/dms-core.secrets.env` via the existing pattern (`SEARCH_CURSOR_SECRET`; if the key is absent a random per-boot key is used and cursors die on restart, which is acceptable in dev). OpenSearch credentials arrive in Phase 2.
- **Config** `SearchProperties` (`@ConfigurationProperties(prefix="dms.search")`, like `NotificationProperties`), keys:
  - `enabled` (global), `opensearch.url`, `connect-timeout` 2s, `read-timeout` 5s, `index-prefix` (default `dms`; tests use a unique prefix), `shards`, `replicas`, `routing-partition-size` (unset in dev).
  - `indexer.{enabled, poll-interval 1s, idle-poll 2s, batch-size 50, lease 2m, max-attempts 8, base-backoff 5s, max-backoff 15m, workers 2}`.
  - `extraction.{max-file-bytes 100MB, max-pages 1000, max-chars 2M, timeout 60s, plain-text-types[]}`.
  - `reconcile.{enabled true, cron "0 30 3 * * *"}` (daily per-tenant reconciler, C2).
  - `query.{default-page-size 20, max-page-size 50, overfetch-factor 3, max-rounds 5, max-candidates 500, time-budget 2500ms, breaker-failures 5, breaker-open 30s}`.
  - Put under `dms.search` in `application.yml`; `application-docker.yml` gets `${OPENSEARCH_URL:...}` placeholders only (its comment forbids literals). `src/test/resources/application.properties` disables the indexer scheduler by default.
- `OpenSearchHealthIndicator` (a `HealthIndicator` bean appears automatically in `DependencyHealthService`, like `OcrJobTokenHealthIndicator`). Phase 1 includes this one bean; the monitoring UI work is S14.
- **CI (report only):** `ci.yml` backend/integration/sonarqube jobs need an `opensearch` service container (`discovery.type=single-node`, `DISABLE_SECURITY_PLUGIN=true`, 512m heap, health wait), `OPENSEARCH_URL`, an update to `.github/scripts/start-it-services.sh`, and a custom ICU image (service containers cannot install plugins, so the image must be pushed to a registry or built in a job step). The Trivy `images` job must scan it. Runner `vm.max_map_count` is usually fine for a 512m heap. I will not edit workflows.
- **Test instance:** `docker run -d --rm --name dms-search-it -p 9201:9200 -e discovery.type=single-node -e DISABLE_SECURITY_PLUGIN=true -e DISABLE_INSTALL_DEMO_CONFIG=true -e OPENSEARCH_JAVA_OPTS="-Xms512m -Xmx512m" dms-opensearch:3.7.0-icu` (no volume). If Docker is unavailable I stop and tell you. Local runs export `OPENSEARCH_IT_REQUIRED=true` so an unreachable node fails instead of skipping; without the flag (for example CI today) OpenSearch ITs skip.

## 5. Mapping, analysers, aliases (S3)
One index `dms-documents-v1` (resource `search/mapping-v1.json`, `dynamic: strict`, `_routing.required: true`, `_source.excludes: ["body"]`).
- **Analysis**
  - `dms_name` = char filter (`_`, `.` to space) + `icu_tokenizer` + `icu_folding`. Not stemmed. Filenames like `Invoice_2024.final.pdf` split into tokens.
  - `dms_text` = `icu_tokenizer` + `icu_folding` + `light_english` stemmer. For body and metadata text.
  - Evidence for choice: ICU tokenizer is the only built-in that word-breaks Thai/CJK and normalises Indic scripts consistently. Sinhala/Tamil are space-delimited so UAX#29 works. A single light stemmer is the compromise for a system with no per-document language. Language detection is out of scope (D13).
- **Fields**

| field | type |
|---|---|
| `tenant_id`, `document_id` | keyword |
| `change_counter`, `generation` | long |
| `title` | text `dms_name`, `index_prefixes{2,5}`; subfields `.stem` (`dms_text`), `.sort` (`icu_collation_keyword`, en) |
| `file_name` | text `dms_name`, `index_prefixes{2,5}` |
| `metadata_text` | text `dms_text` |
| `body` | text `dms_text`, `store: true`, `index_options: offsets`, `position_increment_gap: 100` (highlight source; excluded from `_source`) |
| `document_type_id`, `owner_id`, `owner_department_id`, `folder_id`, `ancestor_folder_ids` (includes self) | keyword |
| `document_type_name`, `lifecycle_state`, `mime_type`, `body_status`, `body_unavailable_reason` | keyword |
| `legal_hold`, `is_deleted`, `purged`, `is_template` | boolean |
| `created_at`, `updated_at`, `indexed_at` | date |
| `size_bytes`, `version_number` | long / integer |
| `metadata` | **nested**: `field_id` keyword, `key` keyword, `v_kw` keyword, `v_num` double, `v_date` date, `v_bool` boolean |

- **Metadata decision (evidence):** field values are keyed by tenant-defined field UUIDs. Dynamic templates or one mapped property per field would let a tenant admin explode the mapping (per-field limit 1000, cluster-state growth), and a strict mapping would reject new fields. A bounded `nested` array of typed value slots keeps the mapping fixed. Only fields with `is_searchable = true` and status active are indexed. Text, long_text, dropdown and multi_select labels, and lookup labels go into `metadata_text` and into `v_kw`. Numbers and decimals go to `v_num`, dates and datetimes to `v_date`, booleans to `v_bool`, `user_ref` as the user id in `v_kw` (no names, they change). Cost: nested queries are slower, but they are filters only. Known limitation: toggling `is_searchable` or changing a field's type needs a tenant reindex (surfaced in admin status, not automatic).
- **Aliases**
  - Write alias `dms-documents-write` (to the current index, `is_write_index`).
  - Per tenant read alias `dms-documents-read-<tenantId>` with `filter: {term: {tenant_id}}` and `routing: <tenantId>`, created lazily at the tenant's first index operation and recorded in `search_tenant_state`. **The API only ever queries that alias.** A mandatory `tenant_id` term filter is also in every query (two independent layers). Ceiling: cluster-state size limits this design to low thousands of tenants (risk R3).
  - Routing key = tenant id; production can set `routing_partition_size` to spread a 500k-document tenant over several shards.
- **Index settings:** `refresh_interval: 1s`, `number_of_shards/replicas` from config (dev: 1/0), `max_ngram_diff` not needed (`index_prefixes`).

## 6. Migrations (S3), re-check the highest number before creating each
**V138__search_index_foundation.sql** (rollback `db/rollback/V138__search_index_foundation.rollback.sql`)
```sql
CREATE SEQUENCE IF NOT EXISTS search_change_seq;

CREATE TABLE search_tenant_state (
  tenant_id        UUID PRIMARY KEY REFERENCES tenants(tenant_id),
  enabled          BOOLEAN     NOT NULL DEFAULT TRUE,           -- per-tenant switch (D9)
  index_ready      BOOLEAN     NOT NULL DEFAULT FALSE,
  generation       BIGINT      NOT NULL DEFAULT 1,              -- bumped by a tenant rebuild
  backfill_state   TEXT        NOT NULL DEFAULT 'none' CHECK (backfill_state IN ('none','running','done','failed')),
  backfill_cursor  UUID,
  last_backfill_at TIMESTAMPTZ,
  last_reconciled_at TIMESTAMPTZ,                               -- C2 daily reconciler
  last_gap         JSONB,                                       -- {"missing":n,"orphans":n}
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE search_index_jobs (
  job_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID        NOT NULL,
  kind            TEXT        NOT NULL CHECK (kind IN ('document','folder_subtree','tenant_purge')),
  document_id     UUID,
  folder_id       UUID,
  reason          TEXT        NOT NULL,
  change_counter  BIGINT      NOT NULL,
  status          TEXT        NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','failed')),
  attempts        INT         NOT NULL DEFAULT 0,
  max_attempts    INT         NOT NULL DEFAULT 8,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  locked_until    TIMESTAMPTZ,
  last_error      TEXT,
  enqueued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_sij_target CHECK (
    (kind='document' AND document_id IS NOT NULL) OR
    (kind='folder_subtree' AND folder_id IS NOT NULL) OR
    (kind='tenant_purge'))
);
CREATE UNIQUE INDEX ux_sij_document ON search_index_jobs (tenant_id, document_id) WHERE kind='document';
CREATE UNIQUE INDEX ux_sij_folder   ON search_index_jobs (tenant_id, folder_id)   WHERE kind='folder_subtree';
CREATE UNIQUE INDEX ux_sij_tenant   ON search_index_jobs (tenant_id)              WHERE kind='tenant_purge';
CREATE INDEX ix_sij_claim ON search_index_jobs (next_attempt_at) WHERE status='pending';
CREATE INDEX ix_sij_failed ON search_index_jobs (tenant_id) WHERE status='failed';

CREATE FUNCTION search_enqueue_document(p_tenant UUID, p_doc UUID, p_reason TEXT) RETURNS void
LANGUAGE sql AS $$
  INSERT INTO search_index_jobs (tenant_id, kind, document_id, reason, change_counter)
  VALUES (p_tenant, 'document', p_doc, p_reason, nextval('search_change_seq'))
  ON CONFLICT (tenant_id, document_id) WHERE kind='document'
  DO UPDATE SET change_counter = EXCLUDED.change_counter, reason = EXCLUDED.reason,
                status = 'pending', attempts = 0, next_attempt_at = now(),
                last_error = NULL, updated_at = now()
$$;
-- triggers (all AFTER ... FOR EACH ROW; documents trigger has a WHEN clause so unrelated updates are free)
--  trg_search_documents: INSERT, or UPDATE OF title, folder_id, owner_user_id, owner_department_id, document_type_id,
--     lifecycle_state, legal_hold, is_deleted, purged_at, is_template, current_version_id, latest_version_id, updated_at
--     (guarded by IS DISTINCT FROM) -> search_enqueue_document(NEW.tenant_id, NEW.document_id, <reason by changed column>)
--  trg_search_doc_metadata: INSERT/UPDATE OF field_values, document_type_id on document_metadata
--  trg_search_versions: INSERT, or UPDATE OF ocr_status, rendition_status, scan_status, purged_at, rendition_object_key,
--     original_filename on document_versions -> its document
--  trg_search_ocr_outputs, trg_search_renditions: INSERT/UPDATE (status, key) -> document via document_versions
--  trg_search_folders: UPDATE OF parent_folder_id, is_deleted on folders -> INSERT folder_subtree job
--  trg_search_tenants: UPDATE OF is_deleted (true) on tenants -> INSERT tenant_purge job
```
**`updated_at` review (user addition):** `updated_at` is in the documents trigger's watched list only so the "modified" date stays fresh. If ordinary non-content updates (for example check-out or lock bookkeeping, workflow status) touch `updated_at`, the trigger would fire for them. Decision rule: when building V138, check which write paths set `documents.updated_at` (read-only inspection of entity listeners/queries); use the C3 5,000-document benchmark in S10 to measure the cost with and without `updated_at` watched; if it causes needless jobs, remove it from the watched columns (the modified date then follows content-bearing changes only) and **record the removal as a numbered deviation**.

The final migration contains the full `CREATE FUNCTION ... LANGUAGE plpgsql` bodies (exact column names are re-verified against V28-V137 at build time; the table/column names above come from the explored entities). Rollback drops the triggers, functions, tables and sequence in reverse order.

**V139__grant_search_permission_to_document_viewers.sql** (additive, same shape as the V134 bookmark backfill): grant `dsr.search-documents` to every role that already has `docm.documents.view`, so no existing user loses search. Rollback removes only grants that this migration added (marker column or a recorded id list, as V134 does).

**Tests:** `V138ToV139SearchMigrationTest` (template `V123ToV125OwnershipMigrationTest`: scratch database, `migrate(url,"137")` then `"139"`, seed rows, run rollbacks in reverse, re-apply). **Per-trigger tests (C3)**, one for each of `trg_search_documents` (each watched column separately, plus an unwatched column that must enqueue nothing), `trg_search_doc_metadata`, `trg_search_versions`, `trg_search_ocr_outputs`, `trg_search_renditions`, `trg_search_folders`, `trg_search_tenants`: perform the real business write (through the service where one exists, otherwise SQL) and assert (a) the write succeeded and its data is committed, (b) **exactly one** job row exists for the target, (c) repeating the change keeps exactly one row with a higher `change_counter` (coalescing), and (d) a rolled-back write leaves no job.

## 7. Index synchronisation (S4/S5)

### 7.1 Event coverage (hook = DB trigger, so every code path including bulk import is covered)
| Event | Today's code | Trigger and reason |
|---|---|---|
| Upload / bulk import | `DocumentVersionPromoter.persist` | INSERT on `documents`, `document_versions`, `document_metadata`; reason `created` |
| New version, check-in, signature | Promoter / `DocumentCheckoutService.checkIn` / signature service | `document_versions` insert, `documents.current_version_id` change; `new_version` |
| OCR finished | `OcrEventsConsumer`, `OcrArtifactWriter` | `ocr_outputs`, `renditions`, `document_versions.ocr_status`, `document_metadata`; `ocr_done` |
| Preview ready or failed | `DocumentRenditionService.generate` | `document_versions.rendition_status`; `rendition` |
| Rename | none exists (finding 3) | `documents.title`; `title` (dormant until a rename path exists) |
| Move document | `DocumentServiceImpl.moveDocuments` | `documents.folder_id`; `move` |
| Folder move / folder delete | `FolderService.move` | `folders` trigger enqueues a `folder_subtree` job, which the indexer expands to document jobs in batches (no long trigger) |
| Metadata edit | `DocumentMetadataServiceImpl.saveValues` | `document_metadata`; `metadata` |
| Owner / department change | `OwnershipTransferEngine` | `documents.owner_*`; `owner` |
| Workflow status | workflow engine | **No index effect** (status is not indexed; workflow-readable access is a layer-2 PostgreSQL check). No trigger. |
| Recycle Bin delete / restore | `deleteDocument`, `RecycleBinService.restore` | `documents.is_deleted`; delete removes the index doc, restore re-indexes |
| Purge | `RecycleBinService.purge*` | `documents.purged_at`; delete op + remove the text artefact (see 7.3) |
| Lifecycle / retention | `DocumentLifecycleService`, `RetentionEngine` | `documents.lifecycle_state`; `lifecycle` |
| Legal hold | `LegalHoldService` | `documents.legal_hold`; `hold` |
| Folder or document access change | `FolderAccessService`, grants | **No index effect**: visibility is verified in PostgreSQL per query (this is the point of D3 layer 2) |
| Tenant removal | `TenantServiceImpl` (soft delete) | `tenant_purge` job: `_delete_by_query` by tenant routing, drop alias |
| Metadata schema change (`is_searchable`, type) | metadata admin | none; admin tenant reindex (documented limitation) |

### 7.2 Indexer algorithm (D10)
- `SearchIndexScheduler` (`@ConditionalOnProperty dms.search.indexer.enabled`, `@Scheduled fixedDelay`) calls `SearchIndexer.runOnce()`.
- **Claim** (copy `DeliveryClaimRepository`): `UPDATE search_index_jobs SET locked_until = now()+lease, attempts = attempts+1 WHERE job_id IN (SELECT job_id FROM search_index_jobs WHERE status='pending' AND next_attempt_at <= now() AND (locked_until IS NULL OR locked_until < now()) ORDER BY next_attempt_at LIMIT :batch FOR UPDATE SKIP LOCKED) RETURNING ...`. Crash recovery is the lease: a dead worker's rows become claimable again after `lease`. Multi-instance safe.
- **Process per job** (each in its own short transaction, never one transaction around OpenSearch I/O):
  1. `document`: load the document, versions, metadata, folder ancestors from PostgreSQL **at processing time** (the DB, not the job, is the truth). If the doc is missing, deleted, purged, a template, or its tenant is disabled or deleted, then bulk **delete**. Otherwise build the index document (`IndexDocumentBuilder`) and **index** it.
  2. `folder_subtree`: page through descendant document ids (recursive CTE, 500 per page) and call `search_enqueue_document` for each.
  3. `tenant_purge`: `_delete_by_query` and drop the alias.
- **Ordering and idempotency:** the job's `change_counter` (from `search_change_seq`, bumped on every enqueue, since the upsert keeps one row per document) is sent as the **external version** (`version_type=external_gte`). A late job can never overwrite newer data. A version conflict is treated as success. Deletes carry a version too.
- **Completion:** `DELETE FROM search_index_jobs WHERE job_id=? AND change_counter=:claimed`. If a newer change arrived while the job ran, the counter differs, so the row survives with `locked_until` cleared and is picked up immediately.
- **Failure:** attempts and `last_error` (class and message, **never document text**), `next_attempt_at = now() + min(maxBackoff, baseBackoff * 2^attempt)`. After `max_attempts` the status becomes `failed` (a visible dead letter, shown by `admin/search/status`, retried by an admin reindex). One failing document never blocks the batch: bulk items are resolved individually.
- **OpenSearch down:** jobs back off and wait in PostgreSQL (nothing is lost); the lag metric rises; search falls back (section 10). When it returns, the queue drains.
- Bulk: one `_bulk` per claimed batch (NDJSON), per-item results mapped back to jobs.

### 7.3 Text sources (D4)
`TextExtractionService` picks the source per current version and records `body_status` (`ready | pending | unavailable`) and a reason (`password_protected, corrupt, unsupported, too_large, timeout, no_text, rendition_failed`).
1. Plain-text types (text/plain, csv, md, json, xml, html): read the original through the existing facade (access and scan checks enforced by `VersionContent`), capped by `max-file-bytes`.
2. Version has a `searchable_pdf` rendition: PDFBox text of that artefact.
3. Scanned or image file with completed OCR: `"text"` from `ocr.json` (via `ocr_outputs.extracted_text_ref`).
4. Otherwise the preview PDF (`rendition_object_key`) through PDFBox. If that gives blank text and OCR is still pending, the status is `pending` and the OCR/rendition trigger re-enqueues when it finishes (this gives the 60s window). If the rendition failed, the status is `unavailable(rendition_failed)`.
- Reads go through `ObjectStorageServiceFactory.resolve(location).download(...)`, which decrypts in the stream. The location comes from `StorageLocationResolver.resolveForTenant(tenant, StoragePurpose.document)` (as in `OcrArtifactWriter`). Exact reuse of `DocumentRenditionService`'s rendition read is checked at build time (read first, reuse a method, do not copy).
- **PDFBox without disk spill:** `Loader.loadPDF(RandomAccessReadBuffer, IOUtils.createMemoryOnlyStreamCache())`, page and char caps, per-document timeout on a bounded executor (declaring an `Executor` bean disables Boot's default one, so a private `ThreadPoolTaskExecutor` field like `ScanWorkPool`, **not** a bean). `InvalidPasswordException` gives `password_protected`.
- Never write plaintext to disk, never log text (log ids, sizes, durations, reasons only).
- **Extracted-text cache (decision O2):** extracted text is written once per version as an encrypted derived artefact `search/{tenant}/{doc}/{version}/text.txt` through the same encrypting storage wrapper. Later metadata-only changes then rebuild the full index document from the cache instead of re-parsing a 1,000-page PDF, and a full rebuild is cheap. It is derived, rebuildable, never plaintext at rest, and removed by the purge path (extend `OcrArtifactWriter`'s purge logic). Alternative: re-extract on every reindex (no new artefact, far more CPU).

### 7.4 Backfill, rebuild, alias swap
- **Backfill** (per tenant, resumable): `search_tenant_state.backfill_state/backfill_cursor`; walks `documents` by id in pages of 500 calling `search_enqueue_document(reason='backfill')`, so it reuses the normal indexer and its text path. `index_ready` becomes true when the cursor is done and the pending backfill jobs for the tenant drain (checked by the status endpoint and the indexer).
- **Tenant rebuild** (`POST /api/v1/admin/search/reindex`, tenant admin, for the caller's tenant): bump `generation`, run the backfill; every re-indexed document carries the new `generation`; when finished, `_delete_by_query` removes the tenant's documents with `generation < current` (orphans). No downtime, searches keep using the old documents until each is overwritten (decision O3).
- **Full rebuild** (mapping or analyser change, admin tool/CLI): create `dms-documents-v2` (`refresh_interval -1`, replicas 0 during load), bulk-load every tenant from PostgreSQL, restore settings, then one atomic `_aliases` call moves the write alias and all per-tenant read aliases from v1 to v2 (live writes during the load are dual-written, see below), then v1 is dropped after a grace period. Live changes during the load go to **both** indices. **A write alias can have only one write index, so the indexer writes to both explicitly**: while a rebuild is in progress `IndexAdmin` exposes `writeTargets()` = [current index, new index] (state kept in a small row/lock visible to all instances, e.g. `search_tenant_state`-style global row or the alias metadata), and `SearchIndexer` sends every bulk item to each target by concrete index name instead of through the write alias. External versions make the duplicate writes idempotent. After the swap `writeTargets()` returns only the new index.
- Lag metric `dms.search.index.lag.seconds` = age of the oldest pending job (+ `jobs.pending`, `jobs.failed` gauges).
- **Daily reconciler (C2):** `SearchReconciler` per tenant: `count(*)` of eligible documents in PostgreSQL vs index `_count` through the tenant alias. On mismatch it pages ids from PostgreSQL, enqueues those missing from the index (checked with `_mget`/`ids` query in pages), and, if the index has more than PostgreSQL, enqueues the extras for deletion. The gap (missing, orphans, time) is stored in `search_tenant_state.last_gap/last_reconciled_at` and returned by `admin/search/status`. Counts are never exposed through the user search API.

## 8. Search API (S6/S7/S8)
Endpoints (all tenant-scoped; cross-tenant ids and unknown ids return 404, never 403):
| Method / path | Request | Response | Permission |
|---|---|---|---|
| `GET /api/v1/search` | `q`, `type` (documentTypeId), `department`, `owner`, `createdFrom/To`, `modifiedFrom/To`, `status` (lifecycle: `active` default, or `inactive,archived`), `folderId` + `includeSubfolders`, `meta=fieldId:op:value` (repeatable; ops `eq,gte,lte,contains`), `sort` (`relevance` default, `title`, `created`, `modified`, with asc/desc), `size` (<=50), `cursor` | `{mode:"search"|"basic", items:[{documentId,title,fileName,documentTypeName,ownerUserId,ownerDepartmentId,createdAt,updatedAt,lifecycleState,legalHold,mimeType,highlights:{title?,fileName?,body?[]},bodyStatus}], total:{value,exact}, nextCursor, notices:[...], didYouMean?}` | `dsr.search-documents`; `status` including inactive/archived also needs `crm.view-archive` (else 403 for that filter only, never silently widened) |
| `GET /api/v1/search/suggest` | `q` (min 2) | `{suggestions:[{documentId,title}]}`, prefix on title/file name, verified | `dsr.search-documents` |
| `POST /api/v1/admin/search/reindex` | none | 202 `{generation}` | tenant admin (pattern verified at build; open question O4) |
| `GET /api/v1/admin/search/status` | none | `{engine:"up|down|red", indexReady, backfillState, pendingJobs, failedJobs, lagSeconds, generation, enabled, lastReconciledAt, gap:{missing,orphans}}` | tenant admin |

Validation: empty query with no filters returns a normal browse-like list (sorted by modified desc) in search mode, not an error. Errors never contain OpenSearch details.

**Query design (`SearchQueryBuilder`)**
```
bool.filter: term tenant_id; term is_deleted=false; term purged=false; term is_template=false;
             terms lifecycle_state (active unless allowed+requested); + user filters
bool.must  : simple_query_string(q, default_operator AND, flags AND|OR|PHRASE|PREFIX|PRECEDENCE|ESCAPE|WHITESPACE,
             fields [title^6, title.stem^3, file_name^5, metadata_text^2, body^1], last bare token turned into token*)
bool.should: match_phrase title^10, match_phrase file_name^8 (phrase boost; quoted phrases handled by simple_query_string)
sort       : relevance = [_score desc, updated_at desc, document_id asc]; others = [<field> , document_id asc] (tiebreaker => stable search_after)
_source    : false (ids and sort values only, in phase 1 of each round)
```
Ranking is title/file name > metadata > body by boosts. A BM25 multiplier of 1 on body keeps long-text noise low. Boosts are config-driven so they can be tuned on real data. `meta` filters are `nested` filters: `{field_id, op}` against `v_kw/v_num/v_date/v_bool`; a metadata field that no longer exists gives the alternate flow "apply the remaining criteria and tell the user" (a notice).

**Two-layer security (D3), `SearchService`:**
1. Layer 1 in OpenSearch: tenant, deleted/purged/template, lifecycle (active only unless `crm.view-archive` held AND requested).
2. Layer 2 `CandidateVerifier`: for each batch of candidate ids load `Document` rows (tenant-scoped, `findAllByDocumentIdInAndTenantIdAndDeletedFalse`) and keep those where `!template && state allowed && purged_at is null && access.canSee(d)` (`DocumentAccessService.forCaller()`, computed once per request). This is exactly the browse rule set, shared through `visibleItems`'s pattern. Browse code is **not edited**; if a small shared helper is extracted from `visibleItems`, existing document tests must stay green.
3. **Over-fetch loop:** `batch = size * overfetch-factor`; `search_after` from OpenSearch until `size` verified rows or a cap (`max-rounds`, `max-candidates`, `time-budget`). Cost worst case is `max-rounds` OpenSearch calls plus `max-rounds` indexed Postgres `IN` queries of <=150 ids. If a cap is hit, return what we have with a "refine your search" notice.
4. **Highlights in a second request:** after verification, one more `_search` limited to the verified ids (same query, `highlight` on title, file_name and stored `body`, 2 fragments of 150 chars, pre/post tags `<mark>` with `encoder: html`). Hidden documents therefore never get highlights computed or returned, and cannot influence response timing through highlighting.
5. **Totals:** never the OpenSearch total. `exact:true` only when the loop exhausted all candidates (OpenSearch returned fewer than the batch), else `{value:N, exact:false}` rendered "N or more". No facet counts.
6. **Cursor:** HMAC-signed, base64url, containing the `search_after` values, the running verified count, the sort, a hash of the filters, tenant and user. A tampered or replayed cursor from another user or filter set returns 400 (no information).
7. **Did you mean:** only when verified results are zero: run a fuzzy query (`fuzziness AUTO`) on title and file name, verify, then pick the closest token from the **verified** titles in Java. No suggester index, so hidden terms can never appear.
8. **No-result guidance:** `notices` carries a code (`NO_RESULTS`) and the UI shows suggestions (check spelling, remove filters, try broader terms, did-you-mean).
9. Generic static notice (always shown, never conditional): "Results only include documents you can access" (so a conditional "some results hidden" message cannot leak that hidden matches exist). Alternate flow "no permission, excluded or shown as restricted": **excluded**.

**Fallback (D9), `SearchFallbackService`:** used when the engine is down (timeouts, circuit breaker open after `breaker-failures`), the alias or index is missing, `search_tenant_state.index_ready=false` or `enabled=false`, or the global switch is off. It reuses `DocumentService.listDocumentsIn` (title contains, plus type, owner, department, lifecycle view) and returns `mode:"basic"` with a notice listing the criteria it could not apply (body text, metadata, date range, folder). Offset cursor in the same envelope. Queries are never logged at INFO (debug at most, redacted).

**Permission constants (S8):** add to **both** `PermissionCodes.java` and `permission-codes.ts`: `DSR_SEARCH_DOCUMENTS = "dsr.search-documents"` (used by `@PreAuthorize` on the search controller, so the "add only once referenced" rule holds). Preset constants are added in Phase 2 when referenced. Search module audit: `SEARCH_REINDEX` in `AuditActionCatalog` under module `DSR` (exists in `permission_modules`).

**Permission matrix**
| Capability | `docm.documents.view` | `dsr.search-documents` | `crm.view-archive` | tenant admin |
|---|---|---|---|---|
| Topbar quick results (unchanged, title search) | required | | | |
| `/search` and `GET /search`, `/suggest` | | required | | |
| Include inactive/archived | | required | required | |
| Reindex / status | | | | required |
Final visibility always via `DocumentAccessService` (folder restrictions, document ACL, workflow-readable, admin).

## 9. UI (S9)
- `lib/search/{api.ts,types.ts,use-search.ts}` using `apiFetchJson` + TanStack Query v5; keys `["search", params]`. `permission-codes.ts` gets the constant.
- `app/search/page.tsx` (`"use client"` + `Suspense` + `useSearchParams`, the repo pattern; `DashboardShell initialActiveLabel="Search"`) gated by `PermissionGate(DSR_SEARCH_DOCUMENTS)`. State lives in the URL (`?q=&type=&...`) so results are linkable.
- Components in `components/search/`: keyword box (existing `SearchInput`), filter panel (`SearchableSelect` for type, department, owner; two `DateTimePicker`s as from/to for created and modified; `SegmentedControl` for lifecycle with Archive options only if `crm.view-archive`; folder picker with an include-subfolders checkbox; metadata filter rows from `useDocumentTypes`), sort `dropdown`, result card list (title, file icon via `fileKind`, snippet `<mark>` rendered safely: **parse server-escaped markup, allow only `<mark>`**, key metadata, lifecycle badge, legal-hold badge), cursor paging (Previous/Next with a cursor stack, since `TablePagination` is offset-based), "N or more" total, no-results panel with suggestions and did-you-mean, static access note, index-delay note ("newly added or changed documents may take a minute to appear"), fallback banner (`mode:"basic"`), refine hint when capped.
- Result click opens `/documents/[documentId]` (the existing page). Pin and bookmark marks and side-panel preview are Phase 2 (S11).
- Topbar keeps quick results; add a "See all results for “term”" footer row (and Enter-with-modifier) going to `/search?q=`. `nav-config.ts` adds a Search item (permission `DSR_SEARCH_DOCUMENTS`); update the two nav-config tests accordingly.
- Read `node_modules/next/dist/docs/` before writing (Next 16.4.3: `searchParams`/`params` are Promises; `dev` is `--webpack`; `proxy.ts` replaces middleware).

## 10. Behaviour when OpenSearch is down, slow or red
| State | Indexing | Search |
|---|---|---|
| Down / timeout | jobs retry with backoff, queue grows, lag metric rises | fallback to title search with a banner after a failed call; circuit breaker opens for `breaker-open` so requests stop paying the timeout |
| Slow | `read-timeout` 5s then failure handling above; extraction timeout 60s per document | query time budget 2.5s; partial result with refine notice |
| Red / yellow | yellow is normal on a one-node dev cluster; red (primary shards missing) is treated as unavailable | fallback |
| Index empty / `index_ready=false` / tenant switch off | backfill runs when enabled | fallback (`mode:"basic"`), never an empty "No results" that would be wrong |
| Back up | queue drains, lag falls | automatic return to search mode |

## 11. Steps (Phase 1)
Size: S small, M medium, L large. Each step finishes with its tests green against `dms-workflow-pg` 5435 `dms_phase2_it` and `dms-search-it` 9201.

- **S1 Scope and decisions** (S, low). Record answers to section 14 in `docs/search-plan.md`; Progress log + Deviations table. Re-check the migration number.
- **S2 OpenSearch in the platform** (M, medium). Create `infra/local/opensearch/Dockerfile`; compose service, volume, env for dms-core; `SearchProperties`; `application.yml` / `application-docker.yml`; `OpenSearchRestClient` (RestClient, timeouts, breaker) behind `SearchEngine`; `OpenSearchHealthIndicator`; IT support `SearchIntegrationTestSupport` (url from `OPENSEARCH_IT_URL`, unique prefix, cleanup; **skips via assumption when the node is unreachable, fails loudly only when `OPENSEARCH_IT_REQUIRED=true`**, which I set locally). Build with `docker compose build opensearch` then `up -d --no-build opensearch`. Verify the image tag first. Tests: engine contract tests (index, bulk, delete, alias, health) on the disposable node.
- **S3 Mapping, aliases, queue, migrations** (M, medium). `resources/search/mapping-v1.json`; `IndexAdmin` (create/ensure index, aliases, tenant alias); V138, V139 + rollbacks; migration test. Tests: mapping accepts a fully populated document and rejects unknown fields; ICU analysers tokenise Sinhala, Tamil, Thai, accented Latin; prefix and phrase behaviour.
- **S4 Text extraction and indexer** (L, high). `TextExtractionService` (+ four source readers), `TextArtefactStore` (encrypted cache; purge hook), `IndexDocumentBuilder`, `SearchIndexer`, `SearchJobRepository` (claim/complete/fail), scheduler. Tests: extraction per source (OCR json, preview PDF, plain text, searchable PDF, password-protected, corrupt, unsupported, oversized); no plaintext in logs (log capture assertion); no temp files created.
- **S5 Synchronisation** (L, high). Triggers (in V138), folder subtree expansion, delete/purge handling, backfill, tenant rebuild with generation, full rebuild + alias swap, tenant purge, lag/queue metrics, `SearchReconciler` (C2), `admin/search/*`, audit `SEARCH_REINDEX`. Tests: every row of the 7.1 table (with the C3 exactly-one-job assertion); reconciler finds missing documents and orphans, enqueues them, reports the gap in status, and runs once under the advisory lock with two instances; out-of-order jobs, crash recovery (expired lease), retries and dead letter, duplicate enqueue coalescing, rebuild with live writes, alias swap with concurrent queries (no failed query). **Concurrency tests run 5 times** (rule 5).
- **S6 Search API** (L, high). `SearchController`, `SearchService`, `SearchQueryBuilder`, `CursorCodec`, `DidYouMean`, DTOs. Tests: keyword, phrase, prefix, metadata text, body text, ranking order (title hit above metadata above body), highlights only for verified ids, sorting, `search_after` paging with stable tiebreaker, no-results guidance, cursor tamper, query logging redaction.
- **S7 Filters** (M, medium). Type, department, owner, created/modified ranges, lifecycle, folder + subfolders (`ancestor_folder_ids`), metadata typed filters (nested); the "filter value no longer exists" notice. Tests: each filter alone and combined with a keyword.
- **S8 Two-layer security + constants** (M, high). `CandidateVerifier`, over-fetch loop and caps, totals rule, fallback, constants in both permission files, `@PreAuthorize`. Tests: folder restrictions, restricted documents, workflow-readable, hidden, Recycle Bin, purged, archived with and without `crm.view-archive`, **totals and suggestions never reflect hidden documents**, cross-tenant crafted query (404, no data), stale index (a document hidden after indexing never returned), fallback when OpenSearch is down or index empty.
- **S9 Search UI** (L, medium). Section 9. Tests (vitest): page rendering and URL state, filter application, highlight sanitising, paging, no-results, fallback banner, permission gating, topbar footer, nav items. `tsc --noEmit`, eslint on changed files, `bun install --frozen-lockfile` first.
- **S10 Phase 1 tests and scale gate** (M, medium). Cross-cutting scenario suite; **trigger overhead benchmark (C3): bulk-update 5,000 documents (an ownership transfer or equivalent) with the search triggers enabled and disabled, report both durations, the overhead percentage and job-row counts**; freshness test (title and metadata searchable within seconds, body within 60s, measured); opt-in **500,000-document scale gate** (a JUnit tag excluded by default like `perf`): synthetic bulk generator straight into OpenSearch plus a representative Postgres population, p95 query latency targets (target: p95 under 1s for keyword+filter queries, under 2.5s with verification on a restricted-heavy user), indexing throughput, and memory on the 512m-heap node. Reports the numbers and flags if the dev machine cannot honestly run it.

## 12. Requirement mapping
| Requirement | Step |
|---|---|
| DSR-FR-001 keyword/metadata/full text | S4, S6 |
| DSR-FR-002 filters | S7, S9 |
| DSR-FR-003 in-browser preview | Phase 2 S11 (Phase 1 opens the document page) |
| DSR-FR-004 relevance ranking | S6 |
| DSR-FR-005 restricted to accessible | S8 |
| DSR-FR-006 preview fallback/download | Phase 2 S11 |
| DSR-FR-007/008/009 presets | Phase 2 S12, S13 |
| DSR-NFR-001 seconds on large repos | S6, S10 |
| DSR-NFR-002 scales | S3 (routing, shards), S10 |
| DSR-NFR-003 index sync window | S5, S10 (D8) |
| DSR-NFR-004 authorised only | S8 |
| DSR-NFR-005 intuitive controls and guidance | S9 |
| DSR-NFR-006 high availability | S2, S5, S6 fallback (S15 clustering in Phase 2) |
| DSR-NFR-007 presets in 1-2 clicks | Phase 2 |
| DSR-NFR-008 presets private | Phase 2 (D7) |
| Alternate: no match | S6, S9 |
| Alternate: no permission | S8 (excluded) |
| Alternate: unsupported preview / preview failure | Phase 2 S11 |
| Alternate: index out of date | S5, S9 delay note |
| Alternate: very large result set | S6 cap and refine notice, S9 |
| Alternate: save preset (no criteria, duplicate, stale filter value, max reached) | Phase 2 S12, S13 (stale filter value: notice machinery is in S7) |

**Phase 1 exit criteria**
1. Migrations apply and roll back cleanly (up, down, re-apply); `V138ToV139SearchMigrationTest` green.
2. Every event in 7.1 reaches the index; title/metadata within seconds, body within 60s (measured).
3. Keyword, phrase, prefix and metadata queries work; ranking order and highlights verified.
4. Every filter works alone and combined.
5. No hidden document, snippet, count, suggestion or timing difference is observable; tenant isolation tests green.
6. With OpenSearch stopped, `/search` returns `mode:"basic"` title search; with an empty index likewise.
7. Backfill, tenant rebuild and full rebuild with alias swap run without failed queries.
8. All existing document, folder, ownership, relationship, retention, preview, workflow tests are unchanged (known failures per rule 4 excluded).
9. Concurrency tests pass 5 of 5 runs.
10. `tsc --noEmit`, eslint on changed files, vitest green; scale gate result recorded.

## 13. Phase 2 outline (planned in a later session)
S11 preview integration (thumbnails, side-panel preview obeying the preview security policy, download fallback, pinned/bookmarked marks). S12 presets API (private, 50 per user configurable, versioned JSON criteria, unique name per user case-insensitive, duplicate-overwrite and stale-value flows). S13 presets UI (Saved Presets one or two clicks from search). S14 monitoring and audit (metrics, integration-health card, audit events, admin UI for status/reindex). S15 production hardening (authentication, TLS, disk encryption, private network, multi-node and shard layout, backups or rebuild runbook, tenant cleanup, upgrade runbook; delivered as docs + config). S16 final tests and docs.

## 14. Risks and open questions
**Questions: ALL ANSWERED, see section 0.1 (kept for the record).**
- **O1 (security owner, D5):** the index holds readable text. Local dev runs with the security plugin off on an internal-only network. Production needs authentication, TLS, disk encryption and a private network (Phase 2 S15). Does the security owner accept readable text in the index, or does it need field-level or application-level encryption (which would remove full-text search)?
- **O2 (my design):** encrypted extracted-text cache artefact `search/{tenant}/{doc}/{version}/text.txt` (cheap reindexing, one more thing to purge) versus re-extracting on every reindex. Recommended: the cache.
- **O3 (conflict with D2):** "tenant rebuild with alias swap" is impossible in one shared index. Proposed: tenant rebuild = in-place re-index with a `generation` and orphan cleanup; **alias swap only for full rebuilds**. Is that acceptable?
- **O4:** which permission or role guards the admin reindex/status endpoints? I will reuse the existing tenant-admin check I find at build time (`useIsTenantAdmin` on the frontend) unless you prefer a new permission.
- **O5:** V139 grants `dsr.search-documents` to every role that has `docm.documents.view`. OK, or do you want to assign it yourself?

**Risks:**
- R1 Memory: OpenSearch 1.5 GB on top of the stack on a Docker VM of about 7.8 GB (reported, not fixed).
- R2 Trigger write amplification on `documents`; mitigated with a `WHEN` clause and coalescing (one pending row per document).
- R3 Per-tenant aliases limit tenant count to low thousands.
- R4 Over-fetch cost for users who can see very little: capped by rounds, candidates and time budget; they get a refine notice.
- R5 `ocr.json` has never been read in Java; its schema is taken from the worker README and must be confirmed against a real artefact in S4.
- R6 The image tag and plugin availability for 3.7.0 come from web search only and are verified in S2.
- R7 Highlights depend on stored `body`; larger index (about +30% with offsets). Acceptable for the stated scale; measured in S10.
- R8 No document rename path exists today; the hook is dormant until a rename feature is built.
- R9 CI changes are not made here (report only). Per C1 the OpenSearch ITs skip in CI until a service container is added (documented follow-up), so CI will not prove the search behaviour until then.
- R10 Production use is blocked on the security owner's sign-off and S15 controls (O1).

## 15. Execution notes (apply to the build run)
- Never run git commands that change state; never `docker compose up --build` the whole stack; never prune or `down -v`; never touch image `local-ocr-worker`, the dev DB (5433) or the running app. Re-check disk (>= 15 GB free) before Docker work.
- Export `DB_URL=jdbc:postgresql://localhost:5435/dms_phase2_it DB_USERNAME=postgres DB_PASSWORD=workflow` for every Maven run; create the DB fresh if needed. Maven and Docker run outside the sandbox with your permission; if Docker is unavailable I stop.
- Maven runs use `-Dmaven.test.failure.ignore=true`; any unfamiliar failure is checked on a read-only `git archive` of the starting commit in /tmp.

## 16. Verification (end-to-end after build)
Run new and affected tests; concurrency tests 5 times; then the browser walkthrough: search by title, by metadata, by text inside a document, each filter, a document you cannot open, archived documents with and without permission, title-search fallback with OpenSearch stopped, a tenant rebuild, and the scale-gate result.

## Progress log
Test runs: Postgres `dms-workflow-pg` 5435 `dms_phase2_it` (recreated fresh: its Flyway history came from older migration numbering), OpenSearch `dms-search-it` 9201 (image `dms-opensearch:3.7.0-icu`, tag verified with `docker manifest inspect`, amd64+arm64). Env for every run: `JAVA_HOME=jdk-17`, `OPENSEARCH_IT_REQUIRED=true`, `DB_URL=jdbc:postgresql://localhost:5435/dms_phase2_it`, `DB_USERNAME=postgres`, `DB_PASSWORD=workflow`, `./mvnw -o test -Dtest=...`.

| Step | Status | Notes |
|---|---|---|
| S1 | done | Plan copied here; answers recorded in 0.1. |
| S2 | done | compose service + Dockerfile (built only `opensearch`), `SearchProperties`, config keys, `SearchEngine` + `OpenSearchRestEngine` (RestClient), circuit breaker, `OpenSearchHealthIndicator`, `SearchIntegrationTestSupport`. `OpenSearchRestEngineIT` 7/7. |
| S3 | done | `mapping-v1.json`, `IndexAdmin`, V138 + V139 + rollbacks, `V138ToV139SearchMigrationTest` 7/7 (every trigger: write succeeds, exactly one job, coalescing, rollback leaves none), `IndexMappingIT` 7/7. |
| S4 | done | `SearchSourceRepository`, `PdfTextExtractor`, `TextExtractionService`, `TextArtifactStore` (+ purge hook in `OcrArtifactWriter.deleteFor`), `IndexDocumentBuilder`, `SearchJobRepository`, `SearchIndexer`/scheduler, `SearchMetrics`. `TextExtractionServiceIT` 15/15, `SearchSyncIT` 16/16. |

| S5 | done | Triggers (V138), `SearchBackfillService`/scheduler (resumable cursor), `SearchRebuildService` (tenant generation sweep, global rebuild with dual-writes and atomic alias swap), `SearchReconciler` + daily scheduler (advisory lock), `SearchAdminController` (`POST /reindex` 202, `GET /status`, tenant admin only, audited `SEARCH_REINDEX_REQUESTED`), lag/queue gauges. `SearchRebuildIT` 9/9, `SearchAdminApiIT` 3/3; **sync + rebuild ITs (25 tests) run 5 of 5 times green**. |
| S6 | done | `SearchController` (`GET /api/v1/search`, `/suggest`, `/filters`), `SearchService`, `SearchQueryBuilder`, `SearchCursor` (HMAC), `DidYouMean`, `SearchFilterValidator`, `SearchFilterOptions`. `SearchApiIT` 21/21, unit tests 20/20. |
| S7 | done | Type, department, owner, created/modified ranges, lifecycle, folder (+subfolders), typed metadata filters, "filter no longer exists" notice. In `SearchApiIT`. |
| S8 | done | Two layers + `CandidateVerifier` logic inside `SearchService` (PostgreSQL `visibleItems`), over-fetch loop and caps, totals rule, `SearchFallbackService`, `DSR_SEARCH_DOCUMENTS` in `PermissionCodes.java` **and** `permission-codes.ts`. `SearchSecurityIT` 12/12, `SearchFallbackIT` 9/9. |
| S9 | done | `/search` page, filter panel, results, cursor paging, notices, topbar "See all results" (+ Ctrl/Cmd+Enter), nav item. `tsc --noEmit` clean for all new code, eslint clean, vitest **364/364 (53 files)**, 31 new search tests + 6 topbar + 4 nav. |
| S10 | done (gate at reduced size) | `SearchFreshnessIT` 5/5, `SearchScenarioIT` 5/5, `SearchTriggerOverheadIT` 2/2, `SearchScaleIT` (`@Tag("perf")`) **run at 50,000 documents, not 500,000** (see Deviation 12). |

### Measurements
- **Freshness** (indexer on its real 1 s poll): new document by title 1.5 s; by text inside it 0.0 s after the title; rename 1.1 s; metadata edit 1.0 s; new version's text 1.0 s; text after the preview PDF finished 1.9 s. Windows: 10 s and 60 s.
- **Trigger overhead, real 5,000-document ownership transfer** (median of 3, alternating): ON 728 ms, OFF 518 ms; overhead 210 ms = 41% of the transfer, **0.042 ms per document**. SQL level: unwatched columns 204 vs 113 ms (0 jobs); `updated_at` alone 290 vs 116 ms (5,000 jobs); new job rows 311 ms; coalescing 277 ms (still 5,000 rows).
- **`updated_at` decision:** it fires alone only for two rare single-document paths (signature lock/unlock, access-mode changes); every other writer (new version, move, delete, restore) also changes a watched column. Cost is negligible and the modified date stays accurate, so it **stays a watched column** (no deviation).
- **Scale gate at 50,000 documents** (PostgreSQL population in SQL, same documents streamed into OpenSearch, 5% visible to the restricted-heavy user): indexing about 10,000 documents/s; p95 41-55 ms for every query shape for a user who sees everything (common word 47, two words 54, phrase 46, prefix 43, type+department+dates 55, folder+subfolders 48, metadata filters 50, filters only sorted 46, newest first 41); **p95 160-263 ms for a user who sees 5%**; ten cursor pages about 40 ms each; 8 concurrent users 128 searches/s, slowest 96 ms; OpenSearch heap 31% of 512 MB; index 50 MB (about 1 KB per document, so about 500 MB at 500,000). Targets p95 < 1 s and < 2.5 s: **met with a wide margin at 50,000**; 500,000 not run.

## Deviations
| # | Step | Deviation | Reason |
|---|---|---|---|
| 1 | S3 | `icu_folding` is restricted with `unicode_set_filter "[^\\u0900-\\u0DFF\\u0E00-\\u0E7F]"` (Indic scripts and Thai are not folded). | Unrestricted `icu_folding` stripped the virama/combining marks of Tamil and Sinhala (`தமிழ்` became `தமிழ`), making different words collide. Verified against the live node and pinned by `IndexMappingIT`. |
| 2 | S2 | The engine client uses a private **Jackson 2** `ObjectMapper`, not Jackson 3 `JsonNode`. | Finding 6 was wrong: only one main-code file uses Jackson 3 while Jackson 2 `ObjectMapper` is the neighbouring modules' mapper. Strings are exchanged with `RestClient` as explicit UTF-8 bytes. |
| 3 | S2 | Config keys are nested (`dms.search.index.prefix`, `.shards`, `.replicas`) rather than `index-prefix`; `indexer.idle-poll` is declared but unused (a single `poll-interval` drives the scheduler, which drains while there is work). | Cleaner binding; idle-poll proved unnecessary. |
| 4 | S3 | V139's rollback leaves the granted `role_permissions` rows in place (only the history row is removed). | Same convention as V134: the grants are indistinguishable from ones an administrator added; the re-run is idempotent. |
| 5 | S2 | The `openSearch` health indicator is **excluded from the `core` health group** in `application.yml`. | The container probe and deploy gate use `core`; a stopped optional search engine must not fail the probe or roll back a release (same reasoning as `ocrJobToken`). |
| 6 | S4 | Extracted text is stored as one JSON artefact `search/{tenant}/{doc}/{version}/text.json` carrying the inputs fingerprint (`basis`), not a bare `text.txt`. | Lets the cache be ignored when OCR or the preview rebuilds, and records the `unavailable` reason without re-extracting. Still written through the encrypting store; deleted by the purge path. |
| 7 | env | Removed 25 stale migration/rollback files from `target/classes` (an old build before the migrations were renumbered): they made every Flyway test fail with "more than one migration with version 106". Source tree untouched. | Build-output hygiene only. |
| 8 | S5/S6 | The tenant-admin check is `@authz.isTenantAdmin()` (an existing check), written directly in `@PreAuthorize`, with no new permission. | Decision O4. |
| 9 | S6 | `GET /api/v1/search/filters` added (types, departments, owners, searchable metadata fields). | The filter panel must work for anyone allowed to search, without also needing the administration permissions those lists usually sit behind. Names only, own tenant. |
| 10 | S6 | The query's last bare word is sent as `(word OR-prefix)`: written `(word \| word*)`, not `word*` alone. | A wildcard is not analysed, so `shared*` never matched the stemmed body term "share"; the OR keeps both exact/stemmed and prefix matching. Found by `SearchApiIT`. |
| 11 | S8 | Over-fetch defaults raised: `max-rounds` 5 to 6, `max-candidates` 500 to 2,000, new `max-batch` 500 (was a fixed 150); the window doubles each round. | For a user who sees 5% of a repository, 7 of 20 first pages hit the old cap and asked the user to refine, versus 0 of 20 with the new defaults, at similar latency (p95 263 ms vs 330 ms). Measured on the 50,000-document gate with identical data. |
| 12 | S10 | (Superseded: the gate was later run at the full 500,000, see Verification.) The scale gate first ran at **50,000** documents, not 500,000. The harness is size-configurable (`-Dsearch.scale.documents` / `SEARCH_SCALE_DOCUMENTS`, default 500,000) and `@Tag("perf")`. | Free disk fell from 18 GiB to 11-12 GiB during the build (Docker's build cache is 26 GB), below the 15 GB line, and a 500,000-document run writes a couple of GB to Docker and PostgreSQL. Not run; numbers above are 50,000. |
| 13 | S5 | `SearchBackfillService` also creates the index and the tenant's read alias before marking a tenant ready. | A tenant with no documents had no alias, so its searches fell back as if the engine were broken. Found by `SearchFreshnessIT`; pinned by `SearchApiIT`. |
| 14 | S5 | The reconciler always compares ids (two keyset passes) rather than counts first. | One missing document and one orphan cancel out in a count; counts would hide the gap. Ids only, once a day. |
| 15 | S9 | The `/search` page does not use the Next `searchParams` prop; it uses the repo's existing pattern (`"use client"` + `useSearchParams` inside `Suspense`). | Same as `app/documents/page.tsx`; read the Next 16 docs first as required. |

## Verification (second session, 2026-11-11)
- **Scale gate at 500,000 documents, all targets met** (1 shard, 0 replicas, 512 MB heap): indexing 500,000 in 49.8 s = **10,047 documents/s**; p95 for a user who sees everything 23-80 ms (common word 67, two words 80, phrase 60, prefix 33, type+dept+dates 42, folder+subfolders 33, metadata filters 65, filters only sorted 30, newest first 23); p95 for a user who sees 5%: **226 ms** (common word), **249 ms** (two words), **63 ms** (filters only); 0 of 20 first pages needed a refine notice; ten cursor pages 24-36 ms; 8 concurrent users 154 searches/s, slowest 125 ms; node heap **52% of 512 MB**; index **500 MB**. The first attempt crashed in the test's own body generator (integer overflow, fixed) after the PostgreSQL population; the second run passed. PostgreSQL population of 500,000 rows takes about 910 s (the 10x data took 70x the time of 50,000).
- **Images** `opensearch`, `dms-core`, `frontend` built; stack started with `up -d --no-build`; dms-core healthy; OpenSearch green from inside the compose network.
- **Dev database:** V138 and V139 applied in 0.24 s, no errors, 12 search triggers present. First backfill: both tenants `index_ready`, `backfill_state` done, 4 eligible documents = 4 indexed, 0 pending, 0 failed, lag 0; the reconciler gap is not recorded yet (daily 03:30 job).
- **Not checked against the live app (no test users, no credentials; only the two real admin accounts exist):** search by title/metadata/text, hidden documents, archive permission, `mode: "basic"` fallback. Covered by the integration tests only. Checked instead: the search endpoints answer 401 without a login; the index holds the 4 documents; stopping OpenSearch leaves dms-core healthy with 0 restarts and no errors, and starting it returns it to green with the index intact.
