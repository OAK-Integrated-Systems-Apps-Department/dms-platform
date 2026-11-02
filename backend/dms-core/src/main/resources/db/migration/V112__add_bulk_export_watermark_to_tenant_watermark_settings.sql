ALTER TABLE tenant_watermark_settings
    ADD COLUMN bulk_export_watermark_enabled BOOLEAN NOT NULL DEFAULT false;
