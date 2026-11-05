-- Online editing, switched by the PLATFORM administrator per organisation (above V128's switch, which the
-- organisation's own administrator keeps).
--
-- Two switches now, both of which must be on before an organisation's users get the editor:
--   tenant_platform_settings.online_editing_allowed   the platform administrator lets this organisation use it at all
--   tenant_editing_settings.enabled (V128)             the organisation's administrator turns it on for its users
--
-- While it is not allowed, the organisation's own Online Editing page and menu item are not shown, "Edit online" is
-- not offered, an edit session is refused, and an editor that is already open can no longer load or save (the file
-- calls re-check it). What the organisation's administrator had chosen is kept, and comes back if it is allowed again.
--
-- No row = not allowed: a new organisation gets online editing only when the platform administrator allows it.

CREATE TABLE tenant_platform_settings (
    tenant_id              UUID        PRIMARY KEY REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    online_editing_allowed BOOLEAN     NOT NULL DEFAULT false,
    updated_by             UUID,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Organisations that already use online editing keep it: nobody is cut off by this change.
INSERT INTO tenant_platform_settings (tenant_id, online_editing_allowed)
SELECT tenant_id, true FROM tenant_editing_settings WHERE enabled
ON CONFLICT (tenant_id) DO NOTHING;
