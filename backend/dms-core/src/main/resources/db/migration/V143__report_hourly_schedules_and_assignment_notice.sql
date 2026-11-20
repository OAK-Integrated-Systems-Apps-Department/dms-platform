-- Custom Report Generation (5.3), follow-up to V138/V140.
--
-- 1. Hourly schedules. report_schedules.frequency may now also be 'hourly': it fires every hour at the minute of time_of_day, in the
--    schedule's own zone. A schedule may only be saved as hourly while the organisation's minimum schedule interval is 'hourly'
--    (which in turn needs dms.reports.allow-hourly-schedules on the platform); ReportScheduleService checks that, not this CHECK.
-- 2. report.schedule-assigned: the new owner of a reassigned schedule is told. standard tier: their own email preference applies.
ALTER TABLE report_schedules DROP CONSTRAINT ck_report_schedules_frequency;
ALTER TABLE report_schedules
    ADD CONSTRAINT ck_report_schedules_frequency CHECK (frequency IN ('hourly', 'daily', 'weekly', 'monthly'));

INSERT INTO notification_categories (category_code, module, tier, default_email_enabled, display_name, description) VALUES
 ('report.schedule-assigned','reports','standard',true,'Report schedule assigned','A report schedule was handed over to you.')
ON CONFLICT (category_code) DO NOTHING;
