-- Document Search & Retrieval, Phase 2 (S12): saved search presets.
--
-- A preset is a user's own, private, named combination of search criteria (DSR-FR-007..009, DSR-NFR-007/008). It stores
-- criteria only: filter references (type, department, owner, folder ids), dates, the user's own keywords and metadata
-- filter values. It never stores a document id, a title, or anything about a result. criteria is versioned
-- (schema_version) so a later change to the shape can migrate old rows in code without DDL.
--
-- saved_search_settings holds the per-tenant cap per user; no row means the configured default (dms.search.presets.default-max),
-- the same shape as pin_settings.
CREATE TABLE saved_searches (
    preset_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID         NOT NULL REFERENCES tenants (tenant_id),
    user_id        UUID         NOT NULL REFERENCES users (user_id),
    name           VARCHAR(100) NOT NULL,
    criteria       JSONB        NOT NULL,
    schema_version INTEGER      NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_saved_searches_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_saved_searches_criteria CHECK (jsonb_typeof(criteria) = 'object')
);

-- One name per person, whatever the case or surrounding spaces.
CREATE UNIQUE INDEX ux_saved_searches_user_name ON saved_searches (tenant_id, user_id, lower(btrim(name)));
CREATE INDEX ix_saved_searches_user ON saved_searches (tenant_id, user_id, updated_at DESC);

CREATE TABLE saved_search_settings (
    tenant_id    UUID PRIMARY KEY REFERENCES tenants (tenant_id),
    max_per_user INTEGER     NOT NULL CHECK (max_per_user BETWEEN 1 AND 500),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
