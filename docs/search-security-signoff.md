# Search: security-owner sign-off checklist

Search puts **readable extracted text** into an OpenSearch index. That is the point of full-text search, and it is a different risk from the documents themselves, which are stored encrypted. Production use needs this sheet filled in and signed. Nothing here is done by default: the local stack runs OpenSearch with its security plugin off on an internal network, for development only.

## What the index holds

| Held | Where | Readable by whom |
|---|---|---|
| Title, file name, owner, type, department, folder ids, dates, lifecycle state, legal-hold flag | the index `_source` | anyone with access to the index |
| **Metadata values** of fields marked *searchable* | the index (`metadata_text`, typed value slots) | anyone with access to the index |
| **Extracted body text** of every indexed document version | the index, as a *stored field* (excluded from `_source`, but retrievable by anyone with index access) | anyone with access to the index |
| The same extracted text, as a cache so reindexing is cheap | object storage, `search/{tenant}/{doc}/{version}/text.json`, **encrypted** like every stored object | only through the application |

Hidden-from-you documents are protected by the application (see below), **not** by the index: whoever reaches OpenSearch directly sees every organisation's text. Everything below is about making that impossible.

## Controls required before production use

| # | Control | How to satisfy it | Verified |
|---|---|---|---|
| 1 | **Authentication on** (the security plugin) | `docker-compose.opensearch.yml` leaves it on; a low-privilege `dms-search` user (index patterns `dms-*`, no cluster admin) is the only application login | client side: `OpenSearchSecuredIT` against a secured disposable node; server side: yours |
| 2 | **TLS** on the HTTP layer, certificate chain from your own CA, **host name checked** | certificates in `infra/production/opensearch/certs/`; dms-core trusts only that CA (`OPENSEARCH_TLS_CA_CERT`) | client side: `OpenSearchClientFactoryTest` (wrong CA, wrong host name and no CA are all refused) |
| 3 | **Private network, no published port** | no `ports:` in the override; reachable only from dms-core on the compose network; firewall the host | yours |
| 4 | **Disk encryption** of the index data | put the `opensearch_data` volume on an encrypted device | yours |
| 5 | **Snapshots**, if any, encrypted and access-controlled | the recommended approach is to *rebuild from PostgreSQL* instead, so no snapshot of readable text exists | n/a unless snapshots are used |
| 6 | **Credential handling** | `OPENSEARCH_PASSWORD` is a secret in `secret-keys.txt` (OpenBao), never in a file in the repository, never logged, never in a health detail | `OpenSearchClientFactoryTest`, `OpenSearchSecuredIT` |
| 7 | **Rotation** | rotate the `dms-search` password: change it in OpenSearch's `internal_users.yml` and in OpenBao, then restart dms-core | yours |
| 8 | **Engine access logging** | enable the security plugin's audit log if your policy needs engine-level reads recorded | yours |

## What the application guarantees (tested)

- **Two layers on every query.** Layer 1 is the engine query (organisation, not deleted, not purged, not a template, allowed lifecycle states). Layer 2 re-checks every candidate page in PostgreSQL with the same rules browsing uses (folder access, document access, workflow-readable access). A stale or even a wrong index can only fail to show a document, never reveal one. (`SearchSecurityIT`, `SearchHiddenEverywhereIT`)
- **Tenant isolation twice**: a per-organisation alias with a filter and a routing key, plus a mandatory tenant filter inside every query. (`SearchCrossTenantIT`)
- **Highlights are computed for verified results only**, in a second request; a hidden document never gets a snippet.
- **Totals are never the engine's number** ("12 or more" when not every candidate was checked), and notices are generic, so nothing reveals that a hidden match exists. A search that matches only hidden documents returns exactly what a search for nothing returns, byte for byte. (`SearchHiddenEverywhereIT`)
- **"Did you mean" and suggestions are built from verified documents only**; there is no suggester index.
- **Thumbnails, pin and bookmark marks are decided for the verified page only**, and the picture endpoint re-checks access on every request.
- **Saved presets hold criteria only**, are private to their creator (404 for everyone else, administrators included), and never write keywords, names or results to a log or an audit event.
- **Deleting and purging**: a deleted or purged document leaves the index; purging also deletes its encrypted text cache. Removing an organisation empties its part of the index and drops its alias. (`SearchSyncIT`, `SearchTenantRemovalIT`)
- **No plaintext on disk**: extraction streams in memory (no temporary files, no PDFBox spill file); the text cache is encrypted storage. (`TextExtractionServiceIT`)

## Residual risks to accept or mitigate

1. **Anyone with engine access reads all text.** Mitigated by controls 1 to 4; not removable without giving up full-text search (field-level encryption of the body would make it unsearchable).
2. **Object storage keeps the encrypted text cache after an organisation is removed** (soft delete) until its keys are shredded or its documents are purged. The index part is removed at once. If your policy needs the cache gone at removal, say so and a purge of `search/{tenant}/` can be added.
3. **Timing**: a search that matches many hidden documents may take longer than one that matches none (the engine still has to look). The cap on rounds and candidates bounds it; the response never says why.
4. **"N or more" totals** are a deliberate loss of precision to avoid leaking counts.
5. The local stack's engine has **no authentication**. Never expose it.

## Sign-off

| Question | Decision | Name | Role | Date |
|---|---|---|---|---|
| Is readable extracted text in the index acceptable given controls 1 to 8? | | | | |
| Is the residual risk 2 (cache after removal) accepted, or must it be deleted at removal? | | | | |
| Authorised to use search in production | | | | |
