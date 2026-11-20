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


---

## Phase 2 detailed plan


### P2.0 Preconditions checked (read-only)
- **Git:** branch `feature/chamath/search-and-retrieval`, working tree clean, Phase 1 committed as `3591c9b`.
- **Highest Flyway number on every ref** (`git ls-tree` over all heads, remotes and tags): `origin/*` = V137, this branch = **V139**. Phase 2 migrations start at **V140**; re-check before creating each.
- **Disk:** `/` has 14 GiB free (Phase 2 needs at least 5 GiB there: OK). Docker's data folder is `/Volumes/ChamathSSD/DockerDesktop` (settings `DataFolder`), and that disk has 136 GiB free (the 15 GB rule is met there). The project itself sits on `/`. I will report `df -h` for both before any Docker work and ask permission for Docker and Maven.
- **Production compose:** `infra/production/docker-compose.yml` has no OpenSearch service at all. Search in production therefore runs in `mode:"basic"` until S15's profile is deployed. dms-core must be checked to start cleanly without `OPENSEARCH_URL` there (S15 test).

### P2.1 What Phase 1 really built, and where the plan no longer matches
**Built and verified** (1008 unit and 857 integration tests green except two known RabbitMQ OCR ITs; vitest 364/364; 500,000-document search gate passed; images rebuilt, V138/V139 applied to the dev DB, first backfill done): `search/{config,engine,index,extract,query,api,admin,observability}`, V138 (12 triggers, queue, tenant state), V139, single index `dms-documents-v1` behind aliases, encrypted text cache, two-layer security, title-search fallback, tenant rebuild and global rebuild with alias swap, daily reconciler, admin `reindex`/`status`, `/search` page, topbar link, nav item.

**The 15 deviations** (all in the Deviations table above) are accepted as built. The ones Phase 2 depends on: #2 (private Jackson 2 mapper in the engine), #3 (nested config keys `dms.search.index.*`), #5 (`openSearch` health indicator is outside the `core` group), #6 (`text.json` not `text.txt`), #8 (admin check is `@authz.isTenantAdmin()`), #9 (`/search/filters`), #11 (over-fetch caps 6 rounds / 2,000 candidates / batch 500), #14 (reconciler compares ids).

**Half-done or never exercised:**
1. Live smoke checks (title/metadata/text, hidden document, archive permission, `mode:"basic"`) were never run against the real app: no test users. Phase 2 S16 adds a seeded-user script (opt-in, disposable data only) so this can be proven without touching dev data.
2. The reconciler gap was never recorded in the dev DB (daily 03:30). The admin status shows `gap: null` and `lastReconciledAt: null`. S14's admin screen shows "never run" and offers "Run reconcile now" (see S14), which also lets us record the first real gap.
3. `OpenSearchHealthIndicator` exists but `DependencyHealthService.label()` has no case for it, so the monitoring card would show the raw id `openSearch`, and `summarise()` does not whitelist its detail keys. Fixed in S14.
4. **Reactivation gap:** `TenantServiceImpl.reactivateTenant` enqueues nothing and no trigger fires on un-delete, so a reactivated tenant stays de-indexed until a manual reindex. Fixed in S15 (V142 trigger; see below).
5. `SearchMetrics` registers timers and counters lazily, so dashboards read "No data" until the first event; `DmsMetrics` pre-registers. S14 pre-registers.
6. The frontend audit mirror `lib/audit-events/audit-action-catalog.ts` lacks `SEARCH_REINDEX_REQUESTED` (the backend has it). Fixed in S14 together with the preset actions.
7. No pin batch lookup exists (bookmarks have `BookmarkService.status(ids)`); S11 adds `PinService.status`.

**Plan text that no longer matches the code** (corrected by a Corrections table when appended, not by rewriting):
| Where in the plan | Says | Reality |
|---|---|---|
| Title and sections 0, 1 | "Nothing has been built", V137, `search/` is `.gitkeep` | Phase 1 built and committed; highest migration is V139 |
| Section 3 and finding 6 | RestClient + Jackson 3 `JsonNode` | Private Jackson 2 `ObjectMapper` (Deviation 2) |
| Section 4 config keys | `index-prefix`, `indexer.idle-poll`, `query.max-candidates 500`, `max-rounds 5` | `index.prefix`, no idle-poll use, 2,000 and 6, `max-batch 500` (Deviations 3, 11) |
| 7.3 and O2 | cache artefact `text.txt` | `text.json` with an inputs fingerprint (Deviation 6) |
| Section 8 audit | `SEARCH_REINDEX` | `SEARCH_REINDEX_REQUESTED` |
| Section 8 admin status | fields as listed | `Status(engine, searchEnabled, indexReady, backfillState, pendingJobs, failedJobs, lagSeconds, generation, lastReconciledAt, Gap(missing, orphans))`, 202 on reindex |
| Section 11 S10 / Progress log | scale gate "at reduced size" | Run at the full 500,000 on 2026-11-11 (Deviation 12 superseded) |
| Section 13 | Phase 2 outline | Replaced by this detailed plan |
| Section 14 R9 / C1 | CI skips OpenSearch ITs | Still true: CI has no OpenSearch container; S15 delivers a patch file only |

**Facts learned after Phase 1, carried into this plan:**
- **Documented product gap (out of scope):** the normal My Documents upload has no metadata fields. Metadata values are entered on Scan & OCR > Scan documents (`/documents/scan`, PDF and images only, "Save fields" button) or by bulk import. So metadata search and metadata filters only find documents that went through those paths. The user guide (S16) explains how to enter metadata today; no upload change is made.
- The reconciler gap is not yet recorded (item 2 above).
- OpenSearch ITs skip without a node unless `OPENSEARCH_IT_REQUIRED=true`; CI has no OpenSearch container; workflows are not edited.
- The 500,000 gate measured queries against synthetic documents streamed straight into OpenSearch. The real path (triggers, queue, indexer, extraction), a backfill and a global rebuild at that size were **not** measured. S16's real-path benchmark does this (default 100,000, configurable to 500,000).
- Toggling a field's `is_searchable` or changing its type needs a tenant reindex (known limitation). S14 turns this into a visible hint (`reindexRecommended`).

### P2.2 Decisions for Phase 2 (made under D1-D13 and the Phase 1 answers)
- **P1 Marks and thumbnails are decided server-side in `SearchService`, for the verified page only.** Result items gain `thumbnailUrl`, `versionNumber`, `sizeBytes`, `pinned`, `bookmarked`. No new public endpoint is needed to read them, and none is ever indexed.
- **P2 Thumbnail URL is `/api/v1/documents/{id}/thumbnail`** (existing endpoint: needs `docm.documents.view`, re-checks access on every request, 204 when none). It is emitted only for documents that passed layer 2 AND only when the caller holds `docm.documents.view` (search can be granted without it; then `thumbnailUrl` is null and the UI shows the file-type icon). A hidden document never reaches the code that builds a URL.
- **P3 Preview reuses the existing `DocumentPreview` Sheet** (`components/documents/document-preview.tsx`), which already applies `usePreviewPolicy` (print/download/copy), `PageViewer` for restricted policies, the download fallback and `LoadFailed`. No second viewer and no new policy path.
- **P4 Presets are tenant-scoped, user-private rows in PostgreSQL**; criteria are the Phase 1 `SearchRequest` filter fields minus `size` and `cursor`. Running a preset reuses `SearchFilterValidator` and the Notice machinery, so drop-outdated-filter behaviour has one implementation.
- **P5 Per-tenant limit lives in a new table `saved_search_settings(tenant_id, max_per_user)`**, exactly like `pin_settings(tenant_id, max_pins)` with a configured default (`dms.search.presets.default-max: 50`). Evidence: there is no generic tenant-settings table; every feature has its own (`pin_settings`, `tenant_platform_settings`, `tenant_preview_policy`, `tenant_watermark_settings`); the pin limit is the closest analogue (per-user cap, default in config, audited change).
- **P6 Monitoring follows `DmsMetrics` conventions:** closed tag sets, no tenant tag, series pre-registered.
- **P7 No new library.** TLS and basic auth use JDK `SSLContext` and Spring `RestClient` already in the engine.

### P2.3 Migrations (re-check the number before each)
**V140__create_saved_searches.sql** (rollback `db/rollback/V140__create_saved_searches.rollback.sql`)
```sql
CREATE TABLE saved_searches (
  preset_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      UUID        NOT NULL REFERENCES tenants(tenant_id),
  user_id        UUID        NOT NULL REFERENCES users(user_id),   -- column names re-verified against V1-V139
  name           VARCHAR(100) NOT NULL,
  criteria       JSONB       NOT NULL,
  schema_version INTEGER     NOT NULL DEFAULT 1,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_saved_searches_name CHECK (btrim(name) <> ''),
  CONSTRAINT ck_saved_searches_criteria CHECK (jsonb_typeof(criteria) = 'object')
);
CREATE UNIQUE INDEX ux_saved_searches_user_name ON saved_searches (tenant_id, user_id, lower(btrim(name)));
CREATE INDEX ix_saved_searches_user ON saved_searches (tenant_id, user_id, updated_at DESC);

CREATE TABLE saved_search_settings (
  tenant_id    UUID PRIMARY KEY REFERENCES tenants(tenant_id),
  max_per_user INTEGER     NOT NULL CHECK (max_per_user BETWEEN 1 AND 500),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```
Rollback: drop both tables (indexes with them). Test: up, down, up, with seeded rows (template `V138ToV139SearchMigrationTest`).
- Does not store document ids, titles or text: criteria hold type/department/owner/folder ids (configuration and folder references), dates, the user's own keyword text and metadata filter values.
- If the user is removed, presets must go with them: ON DELETE behaviour is verified against how `users` rows are retired (soft delete today: presets simply become unreachable; a hard-delete path, if any, gets `ON DELETE CASCADE`). Recorded at build time.

**V141__grant_preset_permissions_to_search_users.sql** (only if open question Q1 is answered yes): grant `dsr.manage-saved-search-presets.create/update/delete` to every role that holds `dsr.search-documents`. Same shape as V139 and V134 (idempotent, rollback removes only the history row, Deviation 4 convention). `share` is not granted and stays unused.

**V142__search_tenant_reactivation.sql:** `AFTER UPDATE ON tenants WHEN (OLD.is_deleted AND NOT NEW.is_deleted)` trigger that sets `search_tenant_state.index_ready=false, backfill_state='none'` (and `last_backfill_at=NULL`) so the existing backfill scheduler re-indexes the tenant. Rollback drops the trigger and function. Test: soft delete, then reactivate, then assert the backfill runs and the tenant is searchable again.

### P2.4 Preset criteria JSON schema, versioning and migration rule
Stored in `saved_searches.criteria` with `schema_version` also in the column (query-able, and a CHECK-free integer so a migration never needs DDL).
```json
{
  "schemaVersion": 1,
  "q": "invoice 2024",
  "documentTypeId": "uuid", "departmentId": "uuid", "ownerId": "uuid",
  "createdFrom": "2026-01-01T00:00:00Z", "createdTo": null,
  "modifiedFrom": null, "modifiedTo": null,
  "statuses": ["active"],
  "folderId": "uuid", "includeSubfolders": true,
  "meta": [ { "fieldId": "uuid", "op": "eq|gte|lte|contains", "value": "text" } ],
  "sort": "relevance|title|created|modified", "ascending": false
}
```
Rules:
- **Unknown keys are rejected on write** (400 `PRESET_INVALID`), so a preset can never carry anything else (no document ids, no result data). Missing keys mean "not set". `size` and `cursor` are never stored.
- **At least one criterion:** a non-blank `q`, or any of the filters. `sort`, `ascending`, `statuses` equal to `["active"]`, and `includeSubfolders` without a `folderId` do not count. Otherwise 400 `PRESET_NO_CRITERIA`.
- **Archived or inactive in `statuses` requires `crm.view-archive`** at save and at run (at run, a user who lost the permission gets the criteria with archived states dropped and a warning, never a silent widening).
- **Versioning:** `PresetCriteriaMigrator` holds an ordered chain `vN -> vN+1`. On every read the stored JSON is migrated in memory to the current version; the stored row is rewritten only on the next explicit update (a read never writes). A stored version **higher** than the code knows returns 409 `PRESET_VERSION_UNSUPPORTED` on run (the preset is listed, but not runnable) so a rollback of the application cannot misread a newer preset. Adding a filter later = a new optional key, still version 1; renaming or retyping a key = version 2 + a migrator step + a test with a stored v1 fixture. Version 1 fixtures are kept in test resources forever.
- **Validation of values is the Phase 1 `SearchFilterValidator`** (types, ops, date order); bad values are 400 at save time. Values that were valid at save but are stale at run time (deleted department or document type, removed metadata field, folder gone or no longer visible) are dropped at run with a `FILTER_NO_LONGER_EXISTS` notice that names them (existing code, existing text), and the preset itself is not modified.

### P2.5 Steps

#### S11 Preview integration (size M, risk medium)
**Goal:** results show thumbnails, open the existing preview in a side panel, fall back to download, and show pinned and bookmarked marks with quick actions, without any new way to see a document the caller cannot see.
**Backend (files, all existing unless marked new):**
- `search/query/SearchResponse.java` (Item gets `versionNumber`, `sizeBytes`, `thumbnailUrl`, `pinned`, `bookmarked`).
- `search/query/SearchService.java`: after layer 2 and before highlights, one batch call per page: `BookmarkService.status(verifiedIds)` and `PinService.status(verifiedIds)` (new). Never for unverified candidates; never indexed; one extra indexed query each, capped by page size (<= 50, below `MAX_STATUS_IDS`).
- `pin/PinService.java`: new `Set<UUID> status(Collection<UUID> ids)` reusing the `list()` audience rule (pins the user placed plus pins whose audience names them), `document_id IN (:ids)`; also in the basic-mode fallback (`SearchFallbackService`) so marks and thumbnails behave the same in both modes.
- `search/query/SearchFallbackService.java`: same fields.
- Thumbnail URL emitted only when the caller holds `docm.documents.view` (P2). The thumbnail endpoint is unchanged.
**Endpoints:** no new endpoint for reading. Quick actions call the existing `PUT/DELETE /api/v1/bookmarks/{id}` (`bd.bookmark`) and `PUT/DELETE /api/v1/pins/{id}` (`pid.pin`, `pid.unpin`); the buttons show only if the caller holds those permissions (the existing `useHasPermission` pattern).
**Frontend:**
- `lib/search/types.ts` (new Item fields), `components/search/search-results.tsx` (replace the icon tile with `DocumentThumbnail` using `children` as the icon fallback; `PinButton` and `BookmarkButton` in the title row, passing the server-given `bookmarked`/`pinned` so there is no per-row request; `PinnedMark`/`BookmarkedMark` indicators).
- New `components/search/result-preview.tsx`: a thin wrapper that renders `DocumentPreview` for the item (`row` built from the item). Opening is a button/title click with a modifier-free primary action ("Preview") and a secondary "Open document" link to `/documents/{id}`. URL state: `?preview=<documentId>` added to `paramsFromFilters`' sibling so a preview is linkable and Back closes it; it is dropped from the search signature (does not reset paging).
- Fallbacks (all already inside `DocumentPreview`): unsupported type -> download offer; render failure or timeout -> `LoadFailed` plus download; policy `download:block` -> no download and the policy message; restricted policy on an undrawable type -> `blockedByPolicy`. Nothing re-implemented.
**Rules and edge cases:** a result removed from visibility after the page loaded: the preview call returns 404 and the panel shows the existing not-found state; the item is not retried. Pin/bookmark failing (limit reached) shows the existing toast. Items with `thumbnailStatus` pending show the icon (the endpoint returns 204, no error).
**Dependencies:** Phase 1 only.
**Tests (backend IT):** marks present only for verified ids (a hidden pinned document is never counted, never marked); marks are per user (another user pins the same document: not marked for me, except through an audience pin, which is); batch size equals page size, not candidate count (assert query count); thumbnail URL absent for a caller without `docm.documents.view`; thumbnail URL for a document the caller cannot open is never produced AND `GET` on it is 404/403 (direct); basic mode returns the same fields. **Frontend:** thumbnail fallback to icon, pin/bookmark buttons gated by permission and reflecting server marks, optimistic toggle and rollback on error, preview opens and closes via the URL, `blockedByPolicy`, unsupported-type download offer, failure fallback.

#### S12 Saved presets API (size L, risk medium)
**Goal:** private, versioned, capped, audited presets that reuse the Phase 1 request model.
**Files (new unless noted):** `search/preset/{SavedSearchController, SavedSearchService, SavedSearchRepository, PresetCriteria, PresetCriteriaMigrator, PresetLimitService, SavedSearchDto}.java`; `security/rbac/catalog/PermissionCodes.java` (add `DSR_MANAGE_PRESETS_CREATE/UPDATE/DELETE`, and `..._SHARE` declared with the comment "declared, unused"); `frontend/lib/auth/permission-codes.ts` (same constants in the same change); `audit/catalog/AuditActionCatalog.java` + `frontend/lib/audit-events/audit-action-catalog.ts` (see S14); V140 (+ V141 if approved); `application.yml` (`dms.search.presets.default-max: 50`).
**Endpoints** (base `/api/v1/search/presets`; all tenant-scoped; any preset that is not the caller's, unknown, or in another tenant answers **404 with the same body as an unknown id**):
| Method and path | Request | Response | Permission |
|---|---|---|---|
| `POST /` | `{name, criteria, overwrite?: boolean}` | 201 `{presetId, name, criteria, schemaVersion, createdAt, updatedAt}` | `dsr.manage-saved-search-presets.create` (+ `dsr.search-documents`) |
| `GET /` | none | `{items:[{presetId, name, summary, updatedAt, runnable}], count, limit}` (no result data) | `dsr.search-documents` |
| `GET /{id}` | none | the preset with criteria (migrated to the current version) | `dsr.search-documents` |
| `PUT /{id}` | `{name?, criteria?}` (at least one) | 200 the updated preset | `dsr.manage-saved-search-presets.update` |
| `DELETE /{id}` | none | 204 | `dsr.manage-saved-search-presets.delete` |
| `POST /{id}/run` | `{size?}` | `{preset:{presetId,name}, criteria (resolved), warnings:[Notice...], result: SearchResponse}` | `dsr.search-documents` |
| `PUT /api/v1/admin/search/presets/limit` | `{maxPerUser}` | 200 `{maxPerUser}` | tenant admin (`@authz.isTenantAdmin()`), audited `SEARCH_PRESET_LIMIT_CHANGED` |
Listing and running need only `dsr.search-documents` (a user who can search can use what they saved, even if an administrator later removes the manage codes); the manage codes gate create, update, delete.
**Rules and edge cases:**
- **Duplicate name** (case-insensitive, trimmed, per user): `POST` returns 409 `PRESET_NAME_EXISTS` with the existing `presetId`; the same request with `overwrite:true` replaces that preset's criteria (keeps id and name casing of the request, bumps `updated_at`) and requires the update permission as well as create. A rename to an existing name on `PUT` is also 409 (no overwrite on rename; the UI offers it via a new `POST ... overwrite`).
- **No criteria:** 400 `PRESET_NO_CRITERIA`. Name blank or over 100: 400 `PRESET_INVALID`.
- **Limit:** per user count against `saved_search_settings.max_per_user` (default 50). At the limit: 409 `PRESET_LIMIT_REACHED` `{limit}`; an overwrite is not blocked by the limit. The check and insert run in one transaction with a per-user advisory lock so two concurrent saves cannot both pass at limit-1 (concurrency test, 5 runs).
- **Run:** loads the preset by id AND tenant AND user; migrates criteria; builds a `SearchRequest` (size from the request, default 20, max 50, no cursor); calls `SearchFilterValidator` (drops outdated filters, builds warnings) then the normal `SearchService` path (so both security layers, fallback, `mode`, totals and notices behave exactly as a typed search; archived states re-checked against `crm.view-archive` now). `resolved criteria` is returned so the UI can fill its form and continue paging with the normal `GET /search` and its cursor. `updated_at` is not touched by a run. Nothing about results is stored.
- **Privacy:** presets store criteria only. Application logs never contain the criteria, keyword text, preset names or results (ids and counts only); audit details carry the preset id and the list of criteria **field names** (never values). A user's presets are never visible to tenant admins through the API (no admin list endpoint); the 404-for-others rule has no admin exception.
- `share` permission: declared, never checked, no code path (a test asserts no controller references it).
**Tests (IT, real PostgreSQL; search-engine dependent ones with the node):** create/list/get/update/rename/delete/run round trips; duplicate name differing by case and spaces; overwrite flow and its permission requirement; no-criteria variants (sort only, active only, subfolders without folder); limit at 50 (configured value 3 in the test), limit raised and lowered, overwrite at limit; 404 for another user's preset and for another tenant's preset with identical body to an unknown id; privacy (admin and other users cannot list); permission matrix (see P2.7); run drops a deleted department, a deleted document type, a removed metadata field and an inaccessible folder and returns the named warnings; run with a lost `crm.view-archive` drops the archived states with a warning; stored v1 fixture migrates; a higher-version row is listed but 409 on run; unknown key in criteria rejected; **no log line contains the keyword** (log capture); concurrent saves at the limit (5 runs); migration up/down.
**Size and risk:** L; medium (limit race, version rules).

#### S13 Presets UI (size M, risk low)
**Goal:** one-click presets within one or two clicks of the search box, with clear messages.
**Files:** new `frontend/lib/search/{presets-api.ts, use-presets.ts}`; new `components/search/{preset-bar.tsx, save-preset-dialog.tsx, manage-presets-dialog.tsx}`; edit `components/search/search-view.tsx` (preset bar beside the keyword box and filter toggle, ~lines 124-145 as mapped), `lib/search/params.ts` (add `preset` handling), `lib/search/types.ts`.
**Behaviour:**
- **Preset bar** next to the keyword box: a "Saved presets" menu (one click to open, a second to run) listing names; "Save as preset" and "Manage". Hidden entirely without `dsr.search-documents`; "Save as preset" and Manage actions are disabled with an explanation (not hidden) without the manage codes, and the list/run still work.
- **One-click run:** calls `POST /{id}/run`, then writes the resolved criteria into the URL (`router.replace`) so the page keeps its URL-as-state pattern (`?q=&type=&...&preset=<id>` is cosmetic: shown as the active chip; the criteria in the URL are what the page actually uses, so editing a filter detaches the chip). Warnings appear as the existing notices banner ("these filters no longer exist and were removed: ...").
- **Save dialog:** name field, a read-only summary of the current criteria, the unsaved-criteria check done client-side (disabled with the message "Add a keyword or a filter first") and enforced by the server. Messages: duplicate name -> an overwrite confirmation dialog ("A preset named X exists. Replace its criteria?"); limit reached -> "You have reached the limit of N presets. Delete one to save a new one." with a Manage button; no criteria; outdated filters at run; unsupported version -> "This preset was saved by a newer version and cannot be run."; 404 on a preset removed elsewhere -> refreshes the list.
- **Manage dialog:** list with rename (inline), "Update to current criteria" (PUT with the form's criteria; disabled when the form has no criteria), delete with confirmation (`AlertDialog`).
**Tests (vitest, mocks like `search-view.test.tsx`):** menu lists and runs a preset and updates the URL; duplicate-name overwrite prompt; no-criteria disabled/message; limit-reached message and manage link; outdated-filter warnings shown; rename/update/delete flows; permission gating (no codes: save and manage disabled, run works; no `dsr.search-documents`: nothing); the URL stays the single state (reload restores the same criteria); keyboard operation and focus return after dialogs. `tsc --noEmit`, eslint on changed files, vitest.

#### S14 Monitoring and audit (size M, risk low)
**Goal:** operations can see search health in the existing dashboard; every preset and admin action is audited; an admin screen replaces curl.
**Backend:**
- `search/observability/SearchMetrics.java`: pre-register all series at construction (closed tags only; no tenant, user, query or id tags, as `DmsMetrics` forbids). Metrics:
  - `dms.search.query` timer, tag `mode` (search|basic) and `outcome` (ok|error|capped) (exists: add `outcome`), plus `dms.search.query.verify.rounds` summary.
  - `dms.search.fallback` counter, tag `reason` (breaker_open|engine_error|index_not_ready|disabled|timeout) (exists; pre-register the closed set).
  - `dms.search.breaker.state` gauge: 0 closed, 1 half-open, 2 open (from `SearchCircuitBreaker`).
  - `dms.search.cluster.status` gauge: 0 green, 1 yellow, 2 red, 3 unreachable, from the same cached health snapshot the indicator uses (no extra requests).
  - `dms.search.bulk.failures` counter, tag `kind` (item|request) (item failures per bulk response, request failures when the whole bulk call errors).
  - `dms.search.preview.failures` counter, tag `reason` (unsupported|failed|timeout|policy_blocked): fed by **POST `/api/v1/search/telemetry/preview`** `{reason}` (closed enum, no ids, `dsr.search-documents`, rate-limited, returns 204), called by the result preview panel when it falls back. Rationale: preview rendering failures are only observable in the browser; the server cannot see them from the thumbnail endpoint alone. (Open question Q3.)
  - Existing gauges (`index.lag.seconds`, `jobs.pending`, `jobs.failed`) and `jobs.processed`, `index.bulk`, `verify.dropped` stay.
- `monitoring/DependencyHealthService.java`: add `case "openSearch" -> "Search (OpenSearch)"` to `label()` and whitelist `cluster` and `search` in `summarise()` (never the `error` text, which can carry a URL); keep it out of the container `core` group (Deviation 5). Test: appears in `GET /api/v1/monitoring/health` with the label and status, and the status is DOWN, not an exception, when the node is stopped.
- `audit/catalog/AuditActionCatalog.java` (module DSR): `SEARCH_PRESET_CREATED`, `SEARCH_PRESET_UPDATED` (rename, criteria update and overwrite), `SEARCH_PRESET_DELETED`, `SEARCH_PRESET_LIMIT_CHANGED`, `SEARCH_RECONCILE_REQUESTED` (admin run-now); `SEARCH_REINDEX_REQUESTED` exists. Severity LOW for preset events, MEDIUM for admin ones. A preset **run** is deliberately not audited (high volume, no state change). Details: preset id, criteria field names, counts; never values or names.
- `search/admin/SearchAdminService.java` + controller: add to `Status` `lastReconcileMissing/orphans` already in `gap`; add `reindexRecommended` (boolean: any searchable metadata field definition changed after `last_backfill_at`; the field table's `updated_at` column is verified at build time) and `reconcileRunning`; add `POST /api/v1/admin/search/reconcile` (202, tenant admin, audited) that runs the existing `SearchReconciler` for the caller's tenant once (rate-limited to one per 5 minutes per tenant). This answers the "gap never recorded" fact without waiting for 03:30.
**Frontend:**
- `lib/audit-events/audit-action-catalog.ts`: add the six actions (including the missing `SEARCH_REINDEX_REQUESTED`) with labels; `audit-badges.ts` if it lists modules.
- Monitoring card needs no code (labels come from the backend).
- New `app/admin/search/page.tsx` + `components/admin/search-status.tsx` + `lib/admin/search-api.ts`; nav item in the Administration section of `components/dashboard/nav-config.ts` with `adminOnly` (matches decision O4, tenant admin). Shows: engine state (up/down/red), search enabled, index ready, backfill state, generation, pending and failed jobs, lag, last reconcile time and gap (or "never run" with a Run now button), a **Reindex** button behind an `AlertDialog` ("This re-reads every document of your organisation. Search keeps working meanwhile and results update gradually."), and the hint "Searchable metadata fields changed since the last reindex: run a reindex so existing documents use the new settings" when `reindexRecommended`. Polls every 10 s while a backfill is running.
**Tests:** metrics registered with closed tags and zero values at startup (no tenant tag anywhere: a test scans all `dms.search.*` meters' tag keys); breaker and cluster gauges change when the node is stopped; bulk failure counters with an injected failing bulk; fallback reasons; preview telemetry rejects any value outside the enum; audit events written for each action with no values in details; frontend mirror test (every backend DSR action present in the TS catalog, via the existing catalog-sync test pattern if there is one, otherwise a new one); admin screen states (up, down, never reconciled, failed jobs, reindex confirm, hint), nav item gating; dependency card.

#### S15 Production hardening (size L, risk high; code support, config and documentation only)
**Constraint:** nothing here changes the running local stack, `infra/local/docker-compose.yml`, the dev DB, or `.github/workflows`. New files only, plus the engine config code.
**Code (engine client):** `search/config/SearchProperties.java` gains `dms.search.opensearch.{username, password, tls.enabled, tls.ca-cert-path, tls.truststore-path, tls.truststore-password, tls.verify-hostname (default true), tls.client-cert-path/key-path (optional mTLS, off)}`; `search/engine/OpenSearchClientFactory.java` (new): builds the `RestClient` with a `JdkClientHttpRequestFactory` over an `HttpClient` using an `SSLContext` built from the custom CA (PEM, no truststore password needed) or JKS/PKCS12, hostname verification on by default (turning it off logs a WARN at startup and is refused unless `dms.search.opensearch.tls.allow-insecure=true` is also set, for disposable tests only), and a `Basic` `Authorization` header from credentials. `OpenSearchRestEngine` keeps its API; nothing else changes. Secrets: `application-docker.yml` takes `${OPENSEARCH_USERNAME}`/`${OPENSEARCH_PASSWORD}` placeholders only; the real values live in the gitignored `infra/secrets/dms-core.secrets.env` (pattern already in `.gitignore`), and the production names are added to `infra/production/secret-keys.txt` so `render-secrets.py` knows them (a tracked names-only file; flagged as a change to review). Passwords are never logged and never appear in the health detail.
**Tests:** unit tests of the factory (CA is trusted, an unrelated CA is rejected, hostname mismatch rejected, auth header present only when configured, disabled TLS keeps plain HTTP, the password is not in `toString()`/logs); an **opt-in** IT `OpenSearchSecuredIT` (env `OPENSEARCH_SECURED_IT=true`, a disposable node `dms-search-it-tls` on port 9202 with the security plugin on and a generated test CA/cert in a temp directory, started by me with your permission; skipped otherwise): connects with auth and TLS, a wrong password and an untrusted CA fail cleanly (breaker/fallback, no secret in error text). If Docker resources are short I report instead and ship the unit tests only.
**Documentation and config deliverables** (all in `docs/search-operations.md` plus new files, none applied):
1. **Production profile:** `infra/production/docker-compose.opensearch.yml` (a new override file, not an edit of `docker-compose.yml`): OpenSearch with the security plugin on, TLS, basic auth, private network only, **no published port**, `mem_limit` and CPU limits, JVM heap = half the memory limit, `ulimits`, `vm.max_map_count` note, health check using credentials. Plus an index-settings guide: replicas of at least 1 (needs 2 or more nodes; a single-node production is yellow and documented as not highly available), shard layout (1 primary per 10-30 GB; for a tenant above about 5 million documents set `routing_partition_size` to 2-4 and reindex; rule of thumb from the measured 1 KB per document), the tenant-routing and per-tenant-alias ceiling (R3), refresh interval, and the disk-encryption requirement (volume-level encryption on the OpenSearch data path).
2. **Backups:** recommendation = **rebuild from PostgreSQL** (it is the source of truth, D1) rather than a snapshot repository, with the **measured** rebuild time (S16's real-path benchmark: global rebuild at the chosen N, extrapolated and flagged as an extrapolation) and the procedure (`POST /admin/search/reindex` per tenant or the global rebuild; what users see meanwhile: `mode:"basic"` until `index_ready`). A snapshot repository variant (S3-compatible, using the existing SeaweedFS/Ceph config) is documented as optional with the exact `_snapshot` calls. (Open question Q4.)
3. **Tenant removal cleanup, verified end to end:** an IT that soft-deletes a tenant and asserts: the `tenant_purge` job runs, the tenant's documents are gone from the index (`_count` 0), the tenant read alias is gone, `search_tenant_state` is not left claiming ready, and the encrypted text cache objects (`search/{tenant}/...`) are unreachable (key shredding path, `keysShreddedAt`) or deleted; the finding that `TenantServiceImpl` has no hard-delete path is documented, and the reactivation gap fix (V142) is tested. If cache objects survive a soft delete, that is reported as a deviation and covered by the existing purge path rather than new deletion code.
4. **Upgrade runbook:** minor OpenSearch upgrades (rolling, image tag pin, plugin `analysis-icu` version must match exactly, the custom image rebuild), mapping changes (v2 index + global rebuild with alias swap, already built), rollback (alias swap back, drop v2 after the grace period), and the pre-upgrade checklist (status endpoint green, queue empty, backup point).
5. **CI patch:** `docs/search-ci.patch` (a ready-to-apply unified diff against `.github/workflows/ci.yml` and `.github/scripts/start-it-services.sh`: an `opensearch` service with the ICU image, `OPENSEARCH_IT_URL`, `OPENSEARCH_IT_REQUIRED=true` in the integration job, the Trivy scan of the image). `.github/workflows` is not edited. The patch is checked with `git apply --check` against a read-only `git archive` copy only (never the working tree).
6. **Security-owner sign-off checklist** (`docs/search-security-signoff.md`): the index holds readable extracted text; what is and is not stored (no `body` in `_source`, but a stored field is readable by anyone with index access); required controls (authentication, TLS, disk encryption, private network, no published port, snapshot encryption if used, access logs, credential rotation); what the application guarantees (two-layer security, tenant filter and routing, highlights only for verified ids, totals never raw); retention and deletion (purge removes the index document and text cache); residual risks (timing, "N or more" totals); sign-off table (name, date, decision) left blank.
**Dependencies:** S14 (health and status) for the runbooks' checks. **Size and risk:** L; high (security-sensitive client code and no way to prove the production cluster here).

#### S16 Final tests and documentation (size L, risk medium)
**Tests (new unless noted; files under `src/test/java/com/oak/dms/search/`):**
- `PermissionMatrixIT`: every endpoint (`GET /search`, `/suggest`, `/filters`, the preset endpoints, admin `reindex`, `status`, `reconcile`, `presets/limit`, telemetry, thumbnail URL presence) against roles: no permission, search only, search + manage, search + view-archive, tenant admin, other tenant admin; expected 200/201/403/404 table.
- `CrossTenantIT`: another tenant's preset id, document id (404, same body as unknown), crafted filter ids, cursor from another tenant (400), another tenant's preset in `run`.
- `HiddenEverywhereIT`: a document the caller cannot see never appears in results, highlights, totals, did-you-mean, suggest, thumbnails (`thumbnailUrl`), pin/bookmark marks, or a preset's run, including a preset saved while the document was visible (the preset stores no ids, so nothing leaks after access is removed).
- `SearchConcurrencyIT`: concurrent index updates (many writers on one document and on many documents, out-of-order change counters, lease expiry, two indexer instances) and concurrent preset saves at the limit; **each concurrency class run 5 times**.
- `SmokeUsersIT` / script `scripts/search-smoke.sh` (opt-in, disposable data only, never the dev DB): creates a tenant, two users (search-only, and search + `crm.view-archive`), a hidden document, an archived document and a text-bearing PDF in the **disposable** database, runs the smoke list from the second session (title, metadata, text, hidden, archived, `mode:"basic"` with the node stopped and back). This closes the gap that the live-app smoke checks could not run.
- **Opt-in REAL-PATH benchmark `SearchRealPathBenchmarkIT`** (`@Tag("perf")`, excluded by default like `SearchScaleIT`; size from `-Dsearch.realpath.documents` or `SEARCH_REALPATH_DOCUMENTS`, default **100,000**, up to **500,000**): load N documents (versions, metadata, folders) into the disposable PostgreSQL by SQL **with the real V138 triggers enabled**; the real `search_index_jobs` queue, the real `SearchIndexer` claim/bulk/complete, with a **stubbed text source** (a test `@Primary` replacement of the text extraction seam returning fixed-size text, so PDFBox and storage are excluded and the measurement is queue + indexer + engine). Measure and report: queue drain time and throughput (documents/s), peak and p95 lag, `jobs.failed` (must be 0), memory of the 512 MB node and dms-core heap, then **a global rebuild with alias swap while a query thread runs continuously** (zero failed queries, swap atomic, p95 during the rebuild), then **a tenant backfill** (generation sweep) at N, then the reconciler gap at N (must be 0). Disk guard: the test refuses to start below 15 GB free on the Docker disk and 5 GB on `/` (checked and reported); scales down in steps (100k, 50k, 25k) and says exactly what ran.
- Existing: all Phase 1 and earlier tests stay green (known RabbitMQ-dependent OCR ITs not fixed). Frontend: the S11/S13/S14 tests above, plus `tsc --noEmit`, eslint on changed files, full vitest.
**Documentation (the same clean style as `ownership-user-guide.md` and `relationships-user-guide.md`: intro paragraph linking the sibling docs, "TL;DR", "Who can do what", task sections, "API summary" table with error codes, "Performance and limits", an opt-in large-data test section):**
- `docs/search-user-guide.md`: what search covers, writing queries (phrases, prefix, filters), results and marks, preview and its restrictions, presets (save, run, rename, overwrite, delete, limits, outdated filters), notices and fallback mode, and **how to enter metadata today** (Scan & OCR > Scan documents > "Save fields", bulk import; not My Documents upload; the documented product gap), and what an administrator does (reindex, hint).
- `docs/events/search-audit-events.md`: every DSR audit action (code, module, severity, when, details, never-included data), in the style of `docs/events/ownership-audit-events.md`.
- `docs/search-traceability.md`: one row per DSR-FR-001..009, DSR-NFR-001..008 and each alternate flow, mapped to code, tests and docs (mirrors `ownership-traceability.md`; starts from the section 12 table).
- API summary: inside the user guide as in the relationships guide, plus the OpenAPI additions if `docs/api/openapi.json` is generated by a build step (checked at build time; not hand-edited if generated).
- `docs/search-operations.md` (S15), `docs/search-security-signoff.md` (S15), `docs/search-ci.patch` (S15).
- Update `docs/search-plan.md`: Progress log rows S11-S16, new deviations, the real-path numbers, the Corrections table.

### P2.6 Dependencies and order
S11 and S12 are independent (both after Phase 1). S13 needs S12. S14 needs S12 for audit and Phase 1 for the rest. S15 needs S14 (health, status, reconcile-now) and the V142 trigger. S16 needs S11-S15 (it tests all of them and writes the traceability). Build order: S11 -> S12 -> S13 -> S14 -> S15 -> S16 (the "start phase 2" run), re-checking the highest migration number and disk before each.

### P2.7 Permission matrix
| Endpoint | none | `dsr.search-documents` | + `manage...create` | + `manage...update` | + `manage...delete` | + `crm.view-archive` | + `docm.documents.view` | tenant admin |
|---|---|---|---|---|---|---|---|---|
| `GET /search`, `/suggest`, `/filters` | 403 | 200 | | | | archived statuses allowed (else 403 for that filter only) | | |
| Result `thumbnailUrl` | | null | | | | | present | |
| Pin / bookmark buttons (existing endpoints) | | | | | | | `pid.pin`, `bd.bookmark` as today | |
| `GET /search/presets`, `GET /{id}`, `POST /{id}/run` | 403 | 200 | | | | archived re-checked at run | | |
| `POST /search/presets` | 403 | 403 | 201 | | | | | |
| `PUT /search/presets/{id}` | 403 | 403 | | 200 | | | | |
| `DELETE /search/presets/{id}` | 403 | 403 | | | 204 | | | |
| overwrite (`POST ... overwrite:true`) | | | needs create **and** update | | | | | |
| Another user's / tenant's / unknown preset | 404 for everyone, no admin exception | | | | | | | |
| `PUT /admin/search/presets/limit`, `POST /admin/search/reindex`, `/reconcile`, `GET /admin/search/status` | 403 | 403 | | | | | | 200/202 |
| `POST /search/telemetry/preview` | 403 | 204 | | | | | | |
`share` is declared, unchecked, unused.

### P2.8 Phase 2 exit criteria
1. Results show thumbnails (or icons) and open the existing preview in the side panel under the preview security policy; each fallback (unsupported, failure, timeout, blocked download) works; pinned and bookmarked marks and quick actions work; marks and thumbnails are computed for verified ids only.
2. Presets: create, list, rename/update, delete, run; unique name per user case-insensitive with the overwrite flow; at least one criterion; per-user limit (default 50) configurable per tenant; private to the creator (404 for everyone else, same body as unknown); outdated filter values dropped on run with warnings naming them; permissions as the matrix; audit events written; nothing about documents stored or logged.
3. UI: Save as preset, Saved presets within two clicks, one-click run via URL state, manage dialog, every message in P2.5 S13.
4. Metrics registered with closed tags, no tenant tag; OpenSearch on the dependency card with a label and a DOWN state when stopped; audit actions in both catalogs; admin Search screen (status, reindex with confirmation, reconcile-now, reindex hint).
5. Production support: client auth and TLS (unit-tested, secured-node IT if resources allow), production profile and guidance, rebuild runbook with a measured time, tenant removal verified end to end incl. reactivation, upgrade runbook, CI patch (not applied), sign-off checklist, `docs/search-operations.md`.
6. Final tests: permission matrix, cross-tenant 404, hidden-everywhere, concurrency 5 of 5 runs, smoke script on disposable data, real-path benchmark result recorded (size stated), frontend tests, docs complete (user guide, audit events, traceability, API summary).
7. All Phase 1 and earlier tests green apart from the known RabbitMQ OCR ITs; migrations apply, roll back and re-apply; `tsc --noEmit`, eslint on changed files, vitest green; nothing committed by me; no workflow, dev DB, running stack or `local-ocr-worker` image touched.

### P2.9 Risks
- **R11 Marks and thumbnails add per-page work** (two batch queries and URL building): bounded by page size (<= 50); measured in S16 against the Phase 1 numbers; budget +10 ms p95.
- **R12 Preset limit race** under concurrent saves: advisory lock plus tested 5 times.
- **R13 Criteria drift:** future filters or renamed keys break stored presets: versioned schema, migrator chain, permanent v1 fixtures, 409 on unknown newer versions.
- **R14 Telemetry endpoint** for preview failures is a new unauthenticated-by-content write path: closed enum, no ids, rate limit, `dsr.search-documents` required; if rejected (Q3) the metric is limited to server-side thumbnail outcomes.
- **R15 Production client code cannot be proven against a real production cluster here:** unit tests plus an opt-in secured disposable node; the sign-off checklist and runbooks make the remaining check explicit.
- **R16 Real-path benchmark at 500,000** costs disk and time (the Phase 1 PostgreSQL population took about 910 s at that size): defaults to 100,000, guarded by the disk rules, scales down and says what ran.
- **R17 Production compose has no OpenSearch:** until S15's profile is deployed, production search is basic mode only; the user guide states it; dms-core starting without `OPENSEARCH_URL` is tested.
- **R18 Memory:** OpenSearch (1.5 GB) plus the secured test node on 9202 plus the 9201 node and PostgreSQL on the same Docker VM; the secured IT is opt-in and I report rather than change Docker's memory.
- **R19 Reactivation and tenant-removal semantics** depend on key shredding behaviour I could only read, not run; the IT in S15 decides, and any gap is reported as a deviation.

### P2.10 Open questions (only genuine ambiguities)
- **Q1** Who gets `dsr.manage-saved-search-presets.create/update/delete`? Without a grant migration nobody can save a preset (the codes are seeded but no role holds them). **Recommended:** V141 grants create/update/delete to every role that holds `dsr.search-documents` (same approach as V139/O5), and `share` stays ungranted. Alternative: no migration, administrators assign the codes.
- **Q2** Should the per-tenant preset limit be changeable from the admin Search screen and `PUT /admin/search/presets/limit`, or is a table value plus the configured default enough (set by SQL)? **Recommended:** include the endpoint and a small field on the admin screen (tenant admin, audited), like the pin limit.
- **Q3** Preview failures "coming from search" can only be seen in the browser. Accept a tiny closed-enum telemetry endpoint `POST /api/v1/search/telemetry/preview` (counter only, no ids)? **Recommended:** yes. Alternative: count only server-side thumbnail outcomes.
- **Q4** Backups: **rebuild from PostgreSQL with a measured time** (recommended; the index is derived data and the rebuild path is already built and tested) or also a snapshot repository (needs repository storage, credentials and a restore test)? The plan documents both but only measures the rebuild.
- **Q5** May S15 add **new files** under `infra/production/` (the OpenSearch override compose file) and add the new secret names to `infra/production/secret-keys.txt`? Existing files in `infra/local/` and `.github/workflows` are not touched; `secret-keys.txt` is the only edit to an existing tracked deployment file. **Recommended:** yes for the new compose file, and yes for the names-only secret list.


### P2.11 Decisions recorded from the user, 2026-11-16 (authoritative; they override anything above that disagrees)
- **Q1 yes.** V141 grants `dsr.manage-saved-search-presets.create`, `.update` and `.delete` to every role that holds `dsr.search-documents`. `share` stays ungranted, and **a test proves it**: after V141 no role holds `...share` because of this migration, and no controller references it.
- **Q2 yes.** `PUT /api/v1/admin/search/presets/limit` (tenant admin, audited `SEARCH_PRESET_LIMIT_CHANGED`) and a field for it on the admin Search screen.
- **Q3 yes, with guards.** `POST /api/v1/search/telemetry/preview`: login required, closed enum only, about **30 calls per minute per user** (excess answers 429), and no other data is stored or logged.
- **Q4** Backups = rebuild from PostgreSQL with a **measured** time. The snapshot repository variant is **documented only**, not built or measured.
- **Q5 yes.** New files under `infra/production/` (the OpenSearch override compose) and the names-only addition to `infra/production/secret-keys.txt`. **Every `infra/production` change is listed in the final report.**
- **A1** The real-path benchmark runs at **100,000** documents; I **ask before going above that** (no 250,000 or 500,000 run without a new answer).
- **A2** `POST /api/v1/admin/search/reindex` and `POST /api/v1/admin/search/reconcile` return **409 while one is running** (the same kind for the same tenant) and are **rate limited** (reconcile: one per 5 minutes per tenant; reindex: a comparable limit, value recorded as a deviation if it differs from the plan). Tests: second call while running gives 409; a call inside the window gives 429; the status shows the running state.
- **A3** The secured TLS IT (`OpenSearchSecuredIT`, port 9202) runs **only if Docker memory allows**; otherwise I report that it was skipped and why, and the unit tests of the client factory remain the proof.
- Git: I never run a git command that changes anything. Nothing is built until you reply "start phase 2".

## Phase 2 progress log (2026-11-16)
Run environment: `dms-workflow-pg` 5435 `dms_phase2_it` and `dms-search-it` 9201 (`dms-opensearch:3.7.0-icu`), `JAVA_HOME=jdk-17`, `OPENSEARCH_IT_REQUIRED=true`. Docker's data folder is `/Volumes/ChamathSSD/DockerDesktop` (136 GiB free, rule: 15 GB); `/` had 13 to 14 GiB free (rule: 5 GB). The running stack and the development database were not touched; its images were not rebuilt, so **the running application still has Phase 1 code and V143 is not applied to the development database until dms-core is rebuilt and restarted**.

| Step | Status | Notes |
|---|---|---|
| S11 | done | `SearchResultMarks` (marks and thumbnail link for the verified page only), `PinService.status`, new item fields (`versionNumber, sizeBytes, thumbnailUrl, pinned, bookmarked`) in search and basic mode; frontend thumbnails, pin and bookmark buttons, `result-preview.tsx` over the existing `DocumentPreview`, `?preview=` URL state, `onFallback` hook. `SearchResultMarksIT` 4/4. |
| S12 | done | V140, V141, V142, `search/preset/*`, `PermissionCodes` (+ declared-unused share), audit actions. `SearchPresetApiIT` 16/16 (round trip, duplicate and overwrite, no criteria, limit and its race, privacy 404, outdated filters, archive, versions, matrix, audit, no log line with keywords), `PresetCriteriaTest` 6/6, `PresetShareUnusedTest` 2/2 (no source and no later migration touches the share code), `V140ToV143SearchPhase2MigrationTest` 4/4 (V141 grants create/update/delete and never share, idempotent). |
| S13 | done | `preset-bar`, `save-preset-dialog`, `manage-presets-dialog`, URL-as-state run, every message of the plan. Frontend tests in `preset-dialogs.test.tsx`, `search-view.phase2.test.tsx`, `presets.test.ts`. |
| S14 | done | `SearchMetrics` rewritten (closed tags, pre-registered, breaker/cluster gauges, bulk and preview counters), `DependencyHealthService` label and detail for `openSearch`, six audit actions in both catalogs, `POST /admin/search/reconcile`, 409/429 on reindex and reconcile (V143), `reindexRecommended`, preset limit endpoints, `POST /search/telemetry/preview` (login, closed enum, 30 per minute per person), admin screen `app/admin/search`. `SearchMetricsTest` 4/4, `OpenSearchHealthIndicatorTest` 2/2, `SearchHealthCardIT` 1/1, `SearchAdminRunStateIT` 4/4, `SearchTelemetryIT` 4/4, `search-status.test.tsx`, `phase2-catalogs.test.ts`. |
| S15 | done (code, config, docs; nothing applied) | `OpenSearchClientFactory` (private CA by PEM or truststore, host name always verified, basic auth), tenant purge hardening, `SearchTenantRemovalIT` 3/3, `docker-compose.opensearch.yml` and templates, `docs/search-operations.md`, `docs/search-security-signoff.md`, `docs/search-ci.patch` (`git apply --check` OK on a clean `git archive`). `OpenSearchClientFactoryTest` 11/11 against real TLS servers; **`OpenSearchSecuredIT` 4/4 against a real OpenSearch 3.7.0 with the security plugin on** (a disposable node on 9202 that replaced the 9201 node for the run because Docker memory was tight; the password appeared nowhere in the log; node removed and 9201 restored afterwards). |
| S16 | done | `SearchPermissionMatrixIT` (15 endpoints x 8 kinds of person, 1 test), `SearchCrossTenantIT`, `SearchHiddenEverywhereIT`, `SearchConcurrencyIT` 2/2, `SearchRealPathBenchmarkIT` (opt-in) run at 100,000, user guide, audit events, traceability, operations guide. |

**Full regression (2026-11-16, after all changes):** backend unit tests **1037, 0 failed, 3 skipped**; backend `*IT` run **917 tests: 888 passed, 13 skipped, 16 failed**, and the 16 are exactly the two known RabbitMQ-dependent OCR ITs (`OcrEventsConsumerLiveIT`, `OcrJobTokenHealthGroupIT`) and the 14 tests of `ImpersonationIT` and `PermissionCodeAuditIT`, which need a live Keycloak admin API (the pom's own `verify` excludes those two classes for that reason); none touches search. Not fixed, as instructed. Frontend: **vitest 426/426 (61 files, was 364/364 in 53)**, `tsc --noEmit` clean, eslint clean on all 27 changed and new files. **Concurrency** (`SearchConcurrencyIT`, the saved-preset limit race, `SearchSyncIT`, `SearchRebuildIT`: 28 tests) **5 of 5 runs green**.

### Phase 2 measurements
- **Real-path benchmark, 100,000 documents** (real triggers, queue and indexer; stubbed text; one node, 1 shard, 0 replicas, 512 MB heap): load with triggers on 8.4 s (about 12,000/s), one coalesced job per document; **queue drain 78.6 s = 1,272 documents/s**, peak lag 86 s, 0 failed jobs; **tenant backfill 120.0 s = 834/s**; **global rebuild with alias swap 136.9 s** (100,164 documents, every tenant in the test database) **while 1,447 queries ran: 0 failed or fell back, p95 104 ms**; reconciler gap 0 missing, 0 orphans in 2.2 s; OpenSearch heap 30% after the drain and 38% at the end; index 39 MB (short stub text); the test JVM (dms-core) used 1,356 MB of a 4,096 MB heap. A 2,000-document dry run on the same code gave 790 documents/s.
- **Not run:** anything above 100,000 (the plan says to ask first; the test refuses without `-Dsearch.realpath.confirm-above=true`).
- The 500,000-document query benchmark of Phase 1 was not repeated.

## Phase 2 deviations
| # | Step | Deviation | Reason |
|---|---|---|---|
| 16 | S14 | New migration **V143** (`last_reindex_requested_at`, `last_reconcile_requested_at`, `reconcile_running_until` on `search_tenant_state`). | Single-flight and rate limits for reindex and reconcile must hold across instances, so the state is in PostgreSQL, not memory. Reindex and reconcile minimum intervals are both 5 minutes (the plan said "a comparable limit" for reindex). |
| 17 | S14 | `dms.search.breaker.state` is 0 closed / 1 open (the plan said 0/1/2). | The breaker has no half-open state to report. |
| 18 | S14 | The fallback metric's closed reason set is the four the code has (`disabled`, `not_ready`, `continuing`, `engine`), not the plan's `breaker_open/engine_error/index_not_ready/disabled/timeout`. | Those are the reasons the service actually distinguishes; unexpected values fold into `engine`. |
| 19 | S12 | Running a preset adds a warning `ARCHIVE_NOT_ALLOWED` when the person no longer holds `crm.view-archive` (archived states dropped). | The plan said "dropped with a warning"; it needed a code. |
| 20 | S15 | Mutual TLS (client certificates) is not supported; the config has no client-cert keys. | The plan listed it as optional/off; basic auth plus TLS with a private CA is what the sign-off needs. |
| 21 | S15 | Host name verification cannot be turned off on its own; turning it off requires `allow-insecure`, which trusts any certificate. | The JDK HTTP client does not allow per-client host name verification to be disabled while keeping the CA check; a combined unsafe switch is clearer and loudly warned about. |
| 22 | S12 | **A Phase 1 flaw fixed:** `SearchResponse` and its records printed titles, highlights and suggestions in `toString`, which Spring writes to the log at DEBUG. They now print counts and ids only (found by `SearchPresetApiIT.noLogLineCarriesTheKeywordsOrNames`). | "No log line carries document or search text." |
| 23 | S15 | **Phase 1 code changed:** `SearchIndexer.purgeTenant` skips a purge for an organisation that has been reactivated since, and resets the tenant's `index_ready` and backfill state after a purge. | Otherwise a late purge could empty a live organisation, and a removed one stayed "ready". `SearchTenantRemovalIT`. |
| 24 | S16 | `SearchScaleIT` (Phase 1) and the new benchmark now need an explicit switch as well as the `perf` tag (`-Dsearch.scale.run=true` or a size; `-Dsearch.realpath.run=true`). | **CI runs `*IT` through surefire, which ignores the pom's `it.excludedGroups`**, so once a CI node exists the 500,000-document test would run there. `docs/search-ci.patch` also adds `-DexcludedGroups=perf`. |
| 25 | S15 | At tenant removal the **encrypted text cache in object storage is not deleted**; the index part is. | Reported as the plan said: no new deletion code; it is unreadable once the keys are shredded and goes with each document's purge. On the sign-off sheet as residual risk 2. |
| 26 | S16 | **No separate smoke script and no seeded live-app run.** The smoke list is covered by `SearchScenarioIT`, `SearchHiddenEverywhereIT`, `SearchSecurityIT`, `SearchFallbackIT` and `SearchPermissionMatrixIT` against real services; the manual list is in `docs/search-operations.md`. | The development database holds only two real administrator accounts and the work must not write development data, so the live-app checks of the second session still could not be run; a script that creates users would need that database. |
| 27 | S11 | A `?preview=<id>` link opens the panel only if that document is on the current page of results. | The panel needs the row's data from the results page; a direct link to a document on another page shows no panel. |
| 28 | S14 | The sidebar's `adminOnly` shows **Search Index** to platform administrators too; the page itself tells anyone who is not a tenant administrator that it is for them. | Existing `adminOnly` semantics. |
| 29 | S15 | **`docs/api/openapi.json` was not regenerated** (it is written by `OpenApiSpecWriter` with watermarking on and has no search section today). | Not part of this run's scope. |

### Every change under `infra/production` (for review)
- `infra/production/docker-compose.opensearch.yml` (**new**, an override file; used only if passed to `docker compose -f`).
- `infra/production/opensearch/opensearch.yml` and `infra/production/opensearch/README.md` (**new** templates).
- `infra/production/secret-keys.txt`: one line added, `OPENSEARCH_PASSWORD`.
- `infra/production/secret-profiles.txt`: one line added, `opensearch OPENSEARCH_PASSWORD`.
- `.gitignore` (repository root): `infra/production/opensearch/certs/` and `infra/production/opensearch/security/` ignored.
- Not changed: `infra/production/docker-compose.yml`, `deploy.sh`, `render-secrets.py`, `infra/local/*`, `.github/workflows/*`.

## Corrections to the Phase 1 text (as of 2026-11-16)
The Phase 1 sections above are left as written; where they disagree with the code, this table is right.

| Where | Says | Reality |
|---|---|---|
| Title, sections 0 and 1 | "Nothing has been built", V137, `search/` holds `.gitkeep` | Built and committed; highest migration is now V143 |
| Section 3 and finding 6 | RestClient with Jackson 3 `JsonNode` | a private Jackson 2 `ObjectMapper` (Deviation 2) |
| Section 4 config keys | `index-prefix`, `idle-poll`, `max-candidates 500`, `max-rounds 5` | `index.prefix`, no idle poll, 2,000 and 6, `max-batch 500` (Deviations 3 and 11) |
| 7.3 and O2 | cache artefact `text.txt` | `text.json` with an inputs fingerprint (Deviation 6) |
| Section 8 audit | `SEARCH_REINDEX` | `SEARCH_REINDEX_REQUESTED` |
| Section 8 admin status | fields as listed | also `searchEnabled`, `reindexRecommended`, `reindexRunning`, `reconcileRunning`; reindex answers 202, 409 or 429 |
| Section 11 S10 and the Phase 1 progress log | scale gate "at reduced size" | run at the full 500,000 on 2026-11-11 (Deviation 12 superseded) |
| Section 13 | Phase 2 outline | replaced by the detailed plan and this log |
| Section 14, R9 and C1 | CI skips the search ITs | still true; `docs/search-ci.patch` is ready, not applied |
| Phase 2 plan P2.1 item 3 | the health card shows a raw `openSearch` id | fixed in S14 |
| Phase 2 plan P2.1 item 4 | reactivation leaves a tenant de-indexed | fixed in S15 (V142 and the purge guard) |
| Phase 2 plan S15, secured IT "port 9202" | an extra node beside 9201 | it replaced the 9201 node for the run (Docker memory) |
