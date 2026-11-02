-- Default-on watermarking for the plain "Download" action (DW).
--
-- tenant_watermark_settings  one row per tenant: whether a plain download (GET
--                            .../documents/{id}/content, inline=false) should have a
--                            watermark applied inline, synchronously, before the bytes
--                            are streamed, and which template to use when it is on.
--                            No row = the platform default, which is off (the same
--                            "no row = default" convention pin_settings (V101) uses).
--
-- This is deliberately NOT the async "Add watermark" job pipeline (watermark_jobs,
-- WatermarkJobWorker, the job queue): that feature is user-initiated and produces a
-- separate copy on request. This table only controls whether the existing plain
-- download path quietly substitutes a watermarked rendering of the same file for the
-- same request - no job row, no queue, no poll.
--
-- default_template_id has no FK ON DELETE action beyond SET NULL: a template is only
-- ever soft-deleted (WatermarkTemplateService#delete), so the row is never actually
-- removed, but SET NULL is the safe fallback if that ever changes - a tenant whose
-- chosen template vanished should fall back to "no default" rather than break downloads.

CREATE TABLE tenant_watermark_settings (
    tenant_id                   UUID        PRIMARY KEY REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    download_watermark_enabled  BOOLEAN     NOT NULL DEFAULT false,
    default_template_id         UUID        REFERENCES watermark_templates (template_id) ON DELETE SET NULL,
    updated_by                  UUID,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);
