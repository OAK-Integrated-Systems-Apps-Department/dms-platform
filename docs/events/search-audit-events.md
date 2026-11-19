# Search audit events (DSR Document Search & Retrieval)

Search itself is **not audited per query**: a search changes nothing, it would be the highest-volume event in the system, and a query's words can be as sensitive as a document. What is audited is what *changes state*: saved presets, the per-organisation limit, and the two administrator actions on the index. Retained per the audit retention policy.

Registered in `AuditActionCatalog` (module `DSR`, a real `permission_modules` code) and mirrored in `frontend/lib/audit-events/audit-action-catalog.ts`; emitted through `AuditService.event(...)`.

## Common fields

| Column | Value |
|---|---|
| `module` | `DSR` |
| `object_type` | `SEARCH_PRESET` (presets and the preset limit), `SEARCH_INDEX` (reindex, check) |
| `object_id` | the preset id; for the limit and the index actions, the organisation's id |
| `actor_*` | the acting user |
| `tenant_id` | always set |
| `outcome` | `SUCCESS` (a refused request writes nothing) |

## Events

| Action | Severity | When | `previous_value` / `new_value` |
|---|---|---|---|
| `SEARCH_PRESET_CREATED` | LOW | a person saved a preset | new: `criteria` = the **names** of the criteria used (for example `["q","documentTypeId","createdFrom"]`) |
| `SEARCH_PRESET_UPDATED` | LOW | a preset was renamed, its criteria replaced, or it was replaced by name (overwrite) | new: `criteria` names when the criteria changed; nothing for a pure rename |
| `SEARCH_PRESET_DELETED` | LOW | a person deleted a preset | none |
| `SEARCH_PRESET_LIMIT_CHANGED` | MEDIUM | a tenant administrator changed how many presets each person may keep | previous / new: `maxPerUser` |
| `SEARCH_REINDEX_REQUESTED` | MEDIUM | a tenant administrator started a reindex of the organisation | new: `generation` |
| `SEARCH_RECONCILE_REQUESTED` | MEDIUM | a tenant administrator started an on-demand check of the index against the database | none |

Running a preset is **not** audited (high volume, no state change).

## Never in an audit event, a log line or a metric

- the **keywords** or any **filter value** of a preset or a search;
- a preset's **name**;
- any **document** id, title, file name, snippet or text;
- the search engine's password or URL.

The tests assert this: `SearchPresetApiIT.createUpdateAndDeleteAreAudited_withFieldNamesNeverValues` (the audit rows contain field names only) and `SearchPresetApiIT.noLogLineCarriesTheKeywordsOrNames` (a log capture over a full create / run / rename / delete cycle finds no keyword or name, including Spring's DEBUG body logging; the request and response records print without their content). `SearchMetricsTest` asserts that no `dms.search.*` metric carries a tenant, person, query or document tag.

## Metrics (not audit, listed for completeness)

`dms.search.query{mode,outcome}`, `dms.search.fallback{reason}`, `dms.search.breaker.state`, `dms.search.cluster.status`, `dms.search.bulk.failures{kind}`, `dms.search.preview.failures{reason}`, `dms.search.index.lag.seconds`, `dms.search.jobs.{pending,failed}`, `dms.search.jobs.processed{kind,outcome}`, `dms.search.index.bulk`, `dms.search.verify.dropped`, `dms.search.query.verify.rounds`. All tag values come from closed sets; none has a tenant tag. See `docs/search-operations.md` for what to alert on.
