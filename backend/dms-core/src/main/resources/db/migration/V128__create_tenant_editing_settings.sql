-- 2.10 Online editing, per organisation (CICO-FR-010): whether it is on, and how it works.
--
-- tenant_editing_settings  one row per tenant. No row = the platform default, which is OFF and exclusive -
--                          the same "no row = default" convention tenant_watermark_settings (V111) and
--                          pin_settings (V101) use. So nothing changes for any tenant until an administrator
--                          with cico.configure-editing-engine.select-provider turns it on in the app.
--
--   enabled   online editing is offered to this tenant's users. It is ALSO subject to the server having an
--             editing engine configured (EDITING_COLLABORA_URL) - a tenant cannot turn on an engine the
--             server does not have, and the server having one does not turn it on for any tenant.
--   mode      exclusive    - opening the editor checks the document out; one editor at a time.
--             simultaneous - no lock; everyone with edit access opens it together (the engine shows who is
--                            present and reconciles edits).
--
-- This replaces the server-wide EDITING_ENABLED / EDITING_MODE environment variables the first version of this
-- feature used: an on/off switch for ONE organisation should be a permission-gated setting in the app, not a
-- restart-and-edit-.env operation that changes it for everybody on the server.

CREATE TABLE tenant_editing_settings (
    tenant_id  UUID        PRIMARY KEY REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    enabled    BOOLEAN     NOT NULL DEFAULT false,
    mode       VARCHAR(20) NOT NULL DEFAULT 'exclusive',
    updated_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_tenant_editing_settings_mode CHECK (mode IN ('exclusive', 'simultaneous'))
);
