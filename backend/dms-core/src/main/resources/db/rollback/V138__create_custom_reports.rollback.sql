-- Manual rollback ("down") for V138__create_custom_reports.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it.
DROP TABLE IF EXISTS report_run_deliveries;
DROP TABLE IF EXISTS report_runs;
DROP TABLE IF EXISTS report_schedule_recipients;
DROP TABLE IF EXISTS report_schedules;
DROP TABLE IF EXISTS report_template_grants;
DROP TABLE IF EXISTS report_templates;
DROP TABLE IF EXISTS report_field_settings;
DROP TABLE IF EXISTS tenant_report_settings;
-- Also: DELETE FROM flyway_schema_history WHERE version = '138';
