-- 5.3 Custom Report Generation: notification categories (global rows, like V125 / V137).
--
--   report.ready              a requested or scheduled report is ready. standard: the user's own preferences are honoured.
--   report.failed             a report run or a delivery failed. critical: mandatory, cannot be switched off.
--   report.schedule-paused    a schedule was paused (owner left, repeated failures, ...). critical: mandatory.
--
-- The brief names these REPORT_READY / REPORT_FAILED / REPORT_SCHEDULE_PAUSED; the lowercase dotted style is how every
-- category in this repo is spelled, so the codes follow the repo.
-- A notification NEVER carries the file or a signed URL - only a link to the authenticated download page.
INSERT INTO notification_categories (category_code, module, tier, default_email_enabled, display_name, description) VALUES
 ('report.ready','reports','standard',true,'Report ready','A report you requested or scheduled is ready to download.'),
 ('report.failed','reports','critical',true,'Report failed','A report could not be generated or delivered.'),
 ('report.schedule-paused','reports','critical',true,'Report schedule paused','A report schedule was paused and needs attention.')
ON CONFLICT (category_code) DO NOTHING;
