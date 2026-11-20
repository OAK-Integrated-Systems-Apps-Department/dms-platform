# Search operations (OpenSearch)

Running, securing, sizing, monitoring, rebuilding and upgrading the search engine behind Document Search & Retrieval. For users see `docs/search-user-guide.md`; for the design `docs/search-plan.md`; for the security owner's checklist `docs/search-security-signoff.md`.

**One rule governs everything below: the index is derived data. PostgreSQL is the source of truth.** Any index can be thrown away and rebuilt from the database. Losing or corrupting the engine costs search quality (users fall back to a title search) and rebuild time, never documents.

## What runs where

| Piece | Where | Notes |
|---|---|---|
| OpenSearch 3.7.0 with the `analysis-icu` plugin | its own container, image `dms-opensearch:3.7.0-icu` (`infra/local/opensearch/Dockerfile`) | local stack: security off, internal network only, no published port. Production: `infra/production/docker-compose.opensearch.yml` |
| Queue of changes | PostgreSQL `search_index_jobs` (filled by 13 triggers, V138/V142) | survives an engine outage: jobs wait and retry with backoff |
| Indexer, backfill, reconciler, rebuilds | inside dms-core (`com.oak.dms.search.index`) | the indexer needs the database and the decryption path, so it is not a separate worker |
| Search API and page | dms-core and the frontend | answer with `mode: "basic"` (title search) whenever the engine cannot |

dms-core never waits for OpenSearch: no `depends_on`, the engine is not part of the container health probe (`core` group), and a stopped engine shows **DOWN** on the system dashboard without failing a deploy. Without `OPENSEARCH_URL` (the current production compose) dms-core starts normally and every search is a title search.

## Configuration

All under `dms.search.*` in `application.yml`; secrets only through the environment.

| Key (environment variable) | Default | Meaning |
|---|---|---|
| `enabled` (`SEARCH_ENABLED`) | true | global switch; off = title search only, nothing indexed |
| `cursor-secret` (`SEARCH_CURSOR_SECRET`) | blank = random per boot | HMAC key of page cursors; set it in production so cursors survive a restart and work across instances |
| `opensearch.url` (`OPENSEARCH_URL`) | `http://localhost:9200` | `https://…` turns TLS on |
| `opensearch.username` / `password` (`OPENSEARCH_USERNAME`, `OPENSEARCH_PASSWORD`) | blank | HTTP basic authentication. **Password is a secret**: OpenBao / `infra/secrets`, never a literal, never logged |
| `opensearch.tls.ca-cert-path` (`OPENSEARCH_TLS_CA_CERT`) | blank | PEM file with your CA; replaces the default trust |
| `opensearch.tls.truststore-path` / `-password` | blank | alternative: a PKCS12 or JKS truststore |
| `opensearch.tls.verify-hostname` | true | cannot be turned off alone: dms-core refuses to start unless `allow-insecure` is also set |
| `opensearch.tls.allow-insecure` | false | trust anything. Disposable test nodes only; a warning is logged at startup |
| `index.prefix`, `index.shards`, `index.replicas`, `index.routing-partition-size` | `dms`, 1, 0, unset | see "Shards and replicas" |
| `indexer.*` | batch 50, lease 2 min, 8 attempts, backoff 5 s to 15 min | the queue worker |
| `reconcile.enabled`, `reconcile.cron` | true, `0 30 3 * * *` | the nightly check of index against database |
| `query.*` | 20 per page (50 max), 6 rounds, 2,000 candidates, 2.5 s budget | the over-fetch caps |
| `presets.default-max` | 50 | saved presets per person; per organisation override in `saved_search_settings` |
| `admin.reindex-min-interval`, `admin.reconcile-min-interval` | 5 min | rate limit of the admin buttons |
| `admin.telemetry-per-minute` | 30 | preview telemetry calls per person per minute |

Mutual TLS (a client certificate) is **not supported**; the engine authenticates dms-core by user and password.

## Production profile

`infra/production/docker-compose.opensearch.yml` is an **override**: nothing changes until you pass it to compose and start the `opensearch` profile.

```
cd infra/production
docker compose -f docker-compose.yml -f docker-compose.opensearch.yml --profile opensearch up -d
```

Before the first start:

1. Issue the node certificate from your CA with the subject alternative name `opensearch`, and put `node.pem`, `node-key.pem`, `root-ca.pem` in `infra/production/opensearch/certs/` (gitignored). The admin certificate for `securityadmin.sh` goes there too.
2. Create the security configuration in `infra/production/opensearch/security/` (gitignored): `internal_users.yml` with a `dms-search` user (hash made with the plugin's `hash.sh`), `roles.yml` with a role for index patterns `dms-*` allowing `indices:data/write/*`, `indices:data/read/*`, `indices:admin/create`, `indices:admin/delete`, `indices:admin/aliases*`, `indices:admin/refresh*`, `indices:admin/settings/update`, `cluster:monitor/health`, and **no** cluster admin; `roles_mapping.yml` mapping it. Load it once with `securityadmin.sh` using the admin certificate.
3. Put `OPENSEARCH_PASSWORD` (and, for the container health check, `OPENSEARCH_HEALTH_USER` / `OPENSEARCH_HEALTH_PASSWORD`) in OpenBao. `OPENSEARCH_PASSWORD` is already in `secret-keys.txt` and `secret-profiles.txt` (profile `opensearch`).
4. Put the data volume on an encrypted device.
5. Push the custom image to your registry and set `OPENSEARCH_IMAGE`.
6. Walk through `docs/search-security-signoff.md`.

After the start: `docker compose logs opensearch`, then the **Search Index** admin screen should show *up*, *index ready* after the first backfill (a few seconds per thousand documents; see the figures below).

**What is verified and what is not.** The client side (TLS with a private CA, host name check, basic auth, refusals, no secret in any message) is tested against real TLS servers (`OpenSearchClientFactoryTest`) and against a real OpenSearch with its security plugin on (`OpenSearchSecuredIT`, 4 of 4 passing, see "Opt-in tests"). The override file and the templates under `infra/production/opensearch/` have **not** been run on a production host: treat them as a starting point and the sign-off checklist as the gate.

### Sizing

Measured on a development machine, one node, 512 MB heap:

- **Index size**: about 1 KB per document with short text (500,000 synthetic documents: 500 MB). Real documents add their text: budget the body text again, plus about 30% for the offsets kept for highlighting.
- **Heap**: 52% of 512 MB at 500,000 documents under a concurrent query load. Production default in the override is a 2 GB heap in a 4 GB container, good for a few million documents.
- **Docker/host memory**: the local stack's engine is limited to 1.5 GB (`OPENSEARCH_MEM_LIMIT`). On a development machine that also runs the disposable test node, keep an eye on the Docker VM memory: it is the reason the secured IT is run in place of, not beside, the plain test node.

### Shards and replicas

- One index (`dms-documents-v1`, behind the write alias and a per-organisation read alias), one primary shard in development.
- **Replicas**: set `index.replicas` to at least 1 once there are two or more nodes. A single node cannot place a replica, stays **yellow**, and is not highly available (the dashboard counts yellow as up).
- **Primary shards**: aim for 10 to 30 GB per shard. At about 1 KB plus text per document that is millions of documents per shard.
- **One very large organisation** (above roughly 5 million documents): set `index.routing-partition-size` to 2 to 4 (spreads one organisation over several shards while keeping it off all of them) and reindex; changing the shard count means a **global rebuild** into a new index.
- **Many organisations**: each organisation has its own read alias (a filter and a routing key). Cluster state grows with the number of aliases, which limits this design to low thousands of organisations; beyond that, split into several clusters.

## Monitoring

| Metric | Meaning | Suggested alert |
|---|---|---|
| `dms.search.index.lag.seconds` | age of the oldest due change waiting to be indexed | above 120 for 10 minutes |
| `dms.search.jobs.pending` | jobs waiting or retrying | rising with no drain |
| `dms.search.jobs.failed` | jobs parked after 8 attempts | above 0 (then **Reindex**, or see the log for the class of error: never document text) |
| `dms.search.cluster.status` | 0 green, 1 yellow, 2 red, 3 unreachable | 2 or 3 for 5 minutes (yellow is normal on one node) |
| `dms.search.breaker.state` | 1 while the circuit breaker refuses requests | 1 for 5 minutes |
| `dms.search.fallback{reason}` | searches answered by the title search (`disabled`, `not_ready`, `continuing`, `engine`) | `engine` rising |
| `dms.search.bulk.failures{kind}` | rejected items or failed bulk requests | any sustained rate |
| `dms.search.query{mode,outcome}` | request latency; `outcome=capped` when the page asked the user to refine | p95 above 1 s |
| `dms.search.preview.failures{reason}` | previews that fell back, reported by the browser | rising |
| `dms.search.verify.dropped`, `dms.search.query.verify.rounds` | how much the permission check removes, and how many rounds a page needed | a person who sees little needs many rounds: expected |

All series exist from startup (zero until the first event); no series has a tenant, person, query or document tag. The system health dashboard lists **Search (OpenSearch)**; the **Search Index** admin screen shows the same state per organisation (engine, readiness, backfill, queue, lag, last check and gap, saved-preset limit).

## Rebuild instead of backup (recommended)

Because the index is derived data, the recovery plan for a lost or damaged index is a **rebuild from PostgreSQL**, not a restore. There is then no copy of readable text outside the live engine.

**Procedure**

1. Bring OpenSearch up (new, empty, same version and plugin).
2. dms-core creates the index and aliases by itself. For each organisation the backfill runs automatically (every 5 minutes it looks for organisations that are not ready), or press **Reindex** on the admin screen, or call `POST /api/v1/admin/search/reindex`.
3. Until an organisation's backfill has finished, its users get the **title search** (`mode: "basic"`), never an empty "no results".
4. For a change of mapping or analysers (a new index version): the **global rebuild** with an atomic alias swap (`SearchRebuildService.rebuildAll`, tested by `SearchRebuildIT` and the benchmark below): the new index is filled while the old one keeps serving, live changes go to both, then one alias call switches over.

**Measured times** (`SearchRealPathBenchmarkIT`, 100,000 documents, 2026-11-16; real PostgreSQL triggers, real queue and indexer, stubbed text source; one node, 1 shard, 0 replicas, 512 MB heap, dms-core running in the test JVM):

| Step | 100,000 documents |
|---|---|
| Load into PostgreSQL with the triggers on (one coalesced job per document) | 8.4 s (about 12,000 documents/s) |
| Queue drain through the real indexer (one loop, as one instance runs) | **78.6 s = 1,272 documents/s**; peak lag 86 s (the whole load waiting at once); 0 failed jobs |
| Tenant backfill (generation sweep) of the same 100,001 documents | **120.0 s = 834 documents/s** |
| Global rebuild with the alias swap, while a query thread ran | **136.9 s** for 100,164 documents (every organisation in the test database); **1,447 queries ran meanwhile, 0 failed or fell back, p95 104 ms** |
| Reconciler on 100,001 documents | gap 0 missing, 0 orphans, 2.2 s |
| OpenSearch heap | 30% of 512 MB after the drain, 38% at the end |
| Index size | 39 MB (the stub text is short; real text adds its own size) |

**Extrapolation (not measured).** At the measured 800 to 1,300 documents a second, one million documents take about **13 to 21 minutes** to rebuild when text is cheap to get. Real extraction (reading encrypted PDFs from object storage, PDFBox, OCR output) is slower than the stub; the encrypted text cache (`search/{tenant}/{doc}/{version}/text.json`) means a rebuild after the first only re-reads that small JSON, not the PDF. Measure a representative sample of your own documents before promising a time. Indexing scales with more dms-core instances (the queue is claimed with `SKIP LOCKED` and a lease).

**Optional: snapshots (documented, not built or measured).** If you choose them anyway: register an S3-compatible repository (for example the existing object store), `PUT _snapshot/dms-repo {"type":"s3",…}`, `PUT _snapshot/dms-repo/nightly-<date>?wait_for_completion=false`, restore with `POST _snapshot/dms-repo/<name>/_restore`. A snapshot is a second copy of readable text: it needs the same controls as the live index (encryption, access control, retention) and must be added to the sign-off sheet. Test a restore before relying on it.

## Tenant removal

Verified end to end by `SearchTenantRemovalIT`:

- Soft-deleting an organisation enqueues a `tenant_purge` job. The indexer deletes that organisation's documents from the index (every write target, including a new index during a rebuild), drops its read alias, and sets its search state to *not ready*. Other organisations are untouched.
- A purge that runs **after** the organisation was reactivated does nothing (it checks first), so a live organisation is never emptied.
- **Reactivation** (V142) resets the organisation's search state so the normal backfill re-indexes it; until then it gets the title search.
- There is no hard-delete path for organisations today.
- **The encrypted text cache in object storage is not deleted at removal.** It is encrypted with the organisation's keys and becomes unreadable when the keys are shredded; documents purged one by one remove their own cache (`OcrArtifactWriter.deleteFor`). If your policy needs the cache deleted at removal, that is a small addition (a purge of `search/{tenant}/`): say so on the sign-off sheet.

## Upgrade runbook

1. **Before**: the admin screen shows *up*, queue empty (0 pending, 0 failed, lag 0), last check shows 0 missing and 0 orphans. Note the time (there is no snapshot to restore: see above).
2. **Minor OpenSearch version** (same major): build the custom image with the new `OS_VERSION` build argument; the `analysis-icu` plugin must be installed at **exactly** the same version as the engine (the Dockerfile does `opensearch-plugin install analysis-icu`; the build fails if there is no matching plugin). Restart the container; dms-core backs off and retries meanwhile, the queue drains afterwards.
3. **Major version**, or any change to the mapping or analysers (`mapping-v1.json`): create `mapping-v2.json`, run the **global rebuild** (new index, dual writes, atomic alias swap). Roll back by swapping the aliases back and dropping the new index after the grace period.
4. **After**: the queue drains, lag returns to 0, `dms.search.cluster.status` is 0 or 1, run **Run check now** and expect 0 missing and 0 orphans.
5. **Rollback of the application** (an older dms-core): saved presets written by a newer version are listed but cannot be run (`PRESET_VERSION_UNSUPPORTED`) instead of being misread.

## CI

CI has **no OpenSearch**, so the search integration tests **skip** there unless `OPENSEARCH_IT_REQUIRED=true` (then an unreachable node fails them loudly). `docs/search-ci.patch` is a ready-to-apply change to the `integration` job of `.github/workflows/ci.yml` (`git apply docs/search-ci.patch`; it was checked with `git apply --check` against a clean copy). It builds the ICU image from `infra/local/opensearch/Dockerfile` (a service container cannot install a plugin), starts a throwaway node, sets `OPENSEARCH_IT_URL` and `OPENSEARCH_IT_REQUIRED=true`, and adds `-DexcludedGroups=perf`.

Why that last flag: the job runs `*IT` through surefire's `-Dtest`, which does not read the pom's `it.excludedGroups`, so the opt-in `@Tag("perf")` tests would run once a node exists. They also guard themselves (`SearchScaleIT` needs `-Dsearch.scale.run=true` or a size; `SearchRealPathBenchmarkIT` needs `-Dsearch.realpath.run=true`): two locks. Also needed in a follow-up: the same node for the `sonarqube` job (its `verify` skips the search ITs without it, so their coverage is not counted) and a Trivy scan of the custom image in the `images` job. Workflows are not edited by the search work.

## Opt-in tests

Never in a normal run. All against **disposable** services, never the development database or the running stack:

```
docker start dms-workflow-pg                     # 5435, user postgres / password workflow, database dms_phase2_it
docker run -d --rm --name dms-search-it -p 9201:9200 -e discovery.type=single-node \
  -e DISABLE_SECURITY_PLUGIN=true -e DISABLE_INSTALL_DEMO_CONFIG=true \
  -e OPENSEARCH_JAVA_OPTS="-Xms512m -Xmx512m" dms-opensearch:3.7.0-icu
export DB_URL=jdbc:postgresql://localhost:5435/dms_phase2_it DB_USERNAME=postgres DB_PASSWORD=workflow OPENSEARCH_IT_REQUIRED=true
```

| Test | Command | Guard |
|---|---|---|
| Search latency at 500,000 documents (`SearchScaleIT`) | `SEARCH_SCALE_DOCUMENTS=500000 ./mvnw -o test -Dtest=SearchScaleIT` | needs the size or `-Dsearch.scale.run=true`; Phase 1 result: p95 23 to 80 ms (sees everything), 226 to 249 ms (sees 5%), index 500 MB, heap 52% |
| The real indexing path (`SearchRealPathBenchmarkIT`) | `SEARCH_REALPATH_RUN=true SEARCH_REALPATH_DOCUMENTS=100000 ./mvnw -o test -Dtest=SearchRealPathBenchmarkIT` | needs the run switch; **above 100,000 also needs `-Dsearch.realpath.confirm-above=true`**; refuses to start below 15 GB free on the Docker disk (`SEARCH_BENCH_DOCKER_DISK`, default `/Volumes/ChamathSSD`) or 5 GB on `/`; writes `target/search-realpath-benchmark.txt` |
| The engine against a secured node (`OpenSearchSecuredIT`) | below | needs `OPENSEARCH_SECURED_IT=true` and the node's password and CA |

Secured node (replace the plain test node, do not run both on a small Docker VM):

```
docker stop dms-search-it
PW="Aa1!$(openssl rand -hex 8)"
docker run -d --rm --name dms-search-it-tls -p 9202:9200 -e discovery.type=single-node \
  -e OPENSEARCH_INITIAL_ADMIN_PASSWORD="$PW" -e OPENSEARCH_JAVA_OPTS="-Xms512m -Xmx512m" dms-opensearch:3.7.0-icu
docker cp dms-search-it-tls:/usr/share/opensearch/config/root-ca.pem /tmp/os-root-ca.pem
OPENSEARCH_SECURED_IT=true OPENSEARCH_SECURED_URL=https://localhost:9202 OPENSEARCH_SECURED_PASSWORD="$PW" \
  OPENSEARCH_SECURED_CA=/tmp/os-root-ca.pem ./mvnw -o test -Dtest=OpenSearchSecuredIT
docker stop dms-search-it-tls; rm /tmp/os-root-ca.pem   # then start dms-search-it again
```

## Troubleshooting

| Symptom | Look at |
|---|---|
| Everybody gets `mode: "basic"` | the dashboard card (engine DOWN?), `dms.search.fallback{reason}`: `not_ready` = backfill not finished, `engine` = unreachable or rejecting, `disabled` = switched off |
| Search finds old text | `index.lag.seconds`, jobs failed; **Run check now** repairs gaps |
| Metadata filters find nothing | the field's *searchable* flag changed after documents were indexed: **Reindex** (the admin screen says so); and note the documented product gap that normal uploads carry no metadata |
| dms-core refuses to start with a TLS message | the message names the missing CA file or the host name option, never a secret |
| `401` from the engine | the `dms-search` user or `OPENSEARCH_PASSWORD`: the engine shows DOWN (the status call fails) and search falls back |
| Failed jobs | `search_index_jobs.last_error` holds the exception class and message (never document text); a failure parks the job after 8 attempts; **Reindex** retries |

## Live smoke checklist (manual)

Automated tests cover each of these against real services (`SearchScenarioIT`, `SearchHiddenEverywhereIT`, `SearchSecurityIT`, `SearchFallbackIT`, `SearchPermissionMatrixIT`). To check the running application by hand you need test users (this environment's development database holds only two real administrator accounts, so it was not done there): one with `dsr.search-documents`, one more with `crm.view-archive`, and a few documents including one restricted away from the first user and one archived.

1. Search by a word in a title, by a metadata value (a document whose fields were saved on Scan & OCR), and by a word inside a document: each is found, with the word highlighted.
2. The restricted document never appears for the first user, in results, count or suggestions.
3. Archived documents appear only for the user with `crm.view-archive`.
4. `docker compose stop opensearch`: the same search answers with the title-search notice (`mode: "basic"`); `docker compose start opensearch`: full search returns, with no restart of dms-core.
5. Save a preset, run it, delete a filter's target (a department) and run it again: a notice names the removed filter.
