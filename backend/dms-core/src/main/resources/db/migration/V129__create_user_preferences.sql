-- Per-user interface preferences. First one: where the online document editor opens.
--
-- user_preferences  one row per user; no row = the defaults below, so nothing changes for anyone until they choose.
--
--   editor_layout  fullpage - the editor takes the whole window (its own slim top bar, no DMS menus around it)
--                  inline   - the editor sits inside the normal DMS page, under the menus and breadcrumb
--
-- Cosmetic only: it changes how the page around the editor is laid out, never what the user may do to a
-- document, so it needs no permission beyond being signed in.

CREATE TABLE user_preferences (
    user_id       UUID        PRIMARY KEY REFERENCES users (user_id) ON DELETE CASCADE,
    tenant_id     UUID        NOT NULL REFERENCES tenants (tenant_id),
    editor_layout VARCHAR(20) NOT NULL DEFAULT 'fullpage',
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_user_preferences_editor_layout CHECK (editor_layout IN ('fullpage', 'inline'))
);
