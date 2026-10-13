-- Manual rollback ("down") for V76__create_workflow_runtime.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Children first;
-- the circular instance -> current stage FK is dropped before its target.
DROP TABLE IF EXISTS workflow_actions;
DROP TABLE IF EXISTS workflow_tasks;
ALTER TABLE IF EXISTS workflow_instances DROP CONSTRAINT IF EXISTS fk_workflow_instances_current_stage;
DROP TABLE IF EXISTS workflow_stage_instances;
DROP TABLE IF EXISTS workflow_instances;
