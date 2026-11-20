# Search: traceability

Where each requirement of the Document Search and Retrieval module (registry section 4.1) is built and tested. Design and decisions: `docs/search-plan.md`. User guide: `docs/search-user-guide.md`.

"Built in" names the real classes; Phase 1 is the search engine, queue, query path and page, Phase 2 is previews, presets, monitoring and production support.

| Requirement | Built in | Tested by |
|---|---|---|
| DSR-FR-001 search by keyword, metadata and text inside documents | `IndexDocumentBuilder`, `TextExtractionService` (plain text, searchable PDF, OCR `ocr.json`, preview PDF), `SearchQueryBuilder`, `GET /search`, `/search` page | `SearchApiIT`, `TextExtractionServiceIT`, `SearchSyncIT`, `SearchScenarioIT`, `SearchFreshnessIT` |
| DSR-FR-002 filter by type, owner, department, dates, status, folder, metadata | `SearchFilterValidator`, `SearchQueryBuilder` (typed nested metadata filters), filter panel | `SearchApiIT` (each filter alone and combined), `SearchFilterOptions` |
| DSR-FR-003 preview a result in the browser | Result **Preview** opens the existing `DocumentPreview` in a side panel (`result-preview.tsx`, `?preview=` URL state) under `usePreviewPolicy`; `thumbnailUrl` for verified results | `SearchResultMarksIT`; `search-view.phase2.test.tsx`, `document-preview.fallback.test.tsx` |
| DSR-FR-004 results ranked by relevance | boosts in `SearchQueryBuilder` (title and file name above metadata above body), `dms.search.query.*` config | `SearchApiIT` (ranking order, highlights) |
| DSR-FR-005 results limited to what the caller may access | two layers in `SearchService` (engine query + PostgreSQL `visibleItems`), `SearchResultMarks` for verified ids only | `SearchSecurityIT`, `SearchHiddenEverywhereIT`, `SearchCrossTenantIT`, `SearchPermissionMatrixIT` |
| DSR-FR-006 preview fallback and download | `DocumentPreview` fallbacks (unsupported type, render failure or timeout, policy `download: block`), `POST /search/telemetry/preview` counts them | `document-preview.fallback.test.tsx`, `SearchTelemetryIT` |
| DSR-FR-007 save a search as a preset | `SavedSearchService`, `SavedSearchController`, V140, V141, Save as preset dialog | `SearchPresetApiIT`, `V140ToV143SearchPhase2MigrationTest`, `preset-dialogs.test.tsx` |
| DSR-FR-008 manage presets (rename, update, delete) | `PUT/DELETE /search/presets/{id}`, Manage dialog | `SearchPresetApiIT`, `preset-dialogs.test.tsx` |
| DSR-FR-009 run a preset | `POST /search/presets/{id}/run` (same validator and search path), Saved presets menu | `SearchPresetApiIT`, `search-view.phase2.test.tsx` |
| DSR-NFR-001 results in seconds on large repositories | over-fetch loop with caps, per-tenant routing | `SearchScaleIT` (500,000 documents, opt-in; p95 23 to 80 ms, and 226 to 249 ms for a person who sees 5%) |
| DSR-NFR-002 scales with the repository | single index, tenant routing, `routing_partition_size` guidance, generation sweep and global rebuild with alias swap | `SearchScaleIT`, `SearchRealPathBenchmarkIT` (opt-in), `SearchRebuildIT` |
| DSR-NFR-003 the index follows changes within a defined window | PostgreSQL triggers (V138) into a coalescing queue, `SearchIndexer`, daily `SearchReconciler` | `V138ToV139SearchMigrationTest` (every trigger), `SearchSyncIT`, `SearchFreshnessIT` (titles and metadata in seconds, text within a minute), `SearchConcurrencyIT` |
| DSR-NFR-004 only authorised people see results | as DSR-FR-005, tenant filter twice, totals never the engine's, generic notices | `SearchSecurityIT`, `SearchHiddenEverywhereIT`, `SearchCrossTenantIT` |
| DSR-NFR-005 intuitive controls and guidance | filter panel, notices, no-results guidance, did you mean, index-delay note | `search-view.test.tsx`, `search-view.phase2.test.tsx` |
| DSR-NFR-006 high availability | title-search fallback (`SearchFallbackService`), circuit breaker, queue waits in PostgreSQL while the engine is down, `docker-compose.opensearch.yml` and replica guidance | `SearchFallbackIT`, `OpenSearchRestEngineIT`, `SearchMetricsTest` (breaker and cluster gauges) |
| DSR-NFR-007 presets within one or two clicks | Saved presets menu beside the search box | `search-view.phase2.test.tsx` |
| DSR-NFR-008 presets are private | rows scoped by tenant and user, 404 for everyone else (administrators too) | `SearchPresetApiIT.aPresetIsPrivate…`, `SearchCrossTenantIT`, `SearchPermissionMatrixIT` |
| Alternate: no match | `NO_RESULTS` notice, did you mean | `SearchApiIT`, `SearchHiddenEverywhereIT` |
| Alternate: no permission to a document | the document is excluded, never shown as restricted | `SearchSecurityIT`, `SearchHiddenEverywhereIT` |
| Alternate: preview unsupported or failed | download offer, `LoadFailed`, policy messages | `document-preview.fallback.test.tsx` |
| Alternate: the index is out of date | queue, lag metric and gauge, index-delay note, reconciler | `SearchSyncIT`, `SearchFreshnessIT`, `SearchAdminRunStateIT` |
| Alternate: a very large result set | `REFINE` notice at the caps, "N or more" totals | `SearchSecurityIT` (caps), `SearchApiIT` |
| Alternate: saving a preset (no criteria, duplicate name, outdated filter value, limit reached) | `PRESET_NO_CRITERIA`, `PRESET_NAME_EXISTS` + overwrite, `FILTER_NO_LONGER_EXISTS` warnings on run, `PRESET_LIMIT_REACHED` | `SearchPresetApiIT`, `preset-dialogs.test.tsx` |
| Permission matrix of every endpoint | `@PreAuthorize` per endpoint | `SearchPermissionMatrixIT` |
| Tenant isolation | alias filter and routing, tenant filter in every query, 404 for presets | `SearchCrossTenantIT`, `SearchTenantRemovalIT` |
| Monitoring | `SearchMetrics` (closed tags, pre-registered), the OpenSearch card, admin Search Index screen | `SearchMetricsTest`, `OpenSearchHealthIndicatorTest`, `SearchHealthCardIT`, `search-status.test.tsx` |
| Audit | `AuditActionCatalog` (module DSR), frontend mirror | `SearchPresetApiIT`, `SearchAdminApiIT`, `phase2-catalogs.test.ts`; `docs/events/search-audit-events.md` |
| Production support (TLS, authentication, runbooks, CI patch, sign-off) | `OpenSearchClientFactory`, `docker-compose.opensearch.yml`, `docs/search-operations.md`, `docs/search-ci.patch`, `docs/search-security-signoff.md` | `OpenSearchClientFactoryTest`, `OpenSearchSecuredIT` (opt-in) |
| Concurrency of index updates | `SearchJobRepository` claim (`SKIP LOCKED`, lease), external versions (`external_gte`), completion only if the counter is unchanged | `SearchConcurrencyIT` (run five times), `SearchSyncIT`, `SearchRebuildIT` |
