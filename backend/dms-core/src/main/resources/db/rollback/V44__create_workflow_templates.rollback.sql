-- Manual rollback ("down") for V44__create_workflow_templates.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Run the V45
-- rollback first - workflow runtime tables reference these. Children first.
DROP TABLE IF EXISTS workflow_stage_participants;
DROP TABLE IF EXISTS workflow_stages;
DROP TABLE IF EXISTS workflow_templates;
