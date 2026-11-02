-- 1.4 Digital Signature Integration - approving ANY workflow stage now
-- requires a digital signature; this is no longer a per-stage opt-in (see
-- WorkflowTemplateServiceImpl#applyStage, which as of this change ignores
-- whatever requiresSignature a caller sends and always forces it true).
--
-- Backfills every existing stage (including soft-deleted ones, so a restored
-- stage doesn't resurrect the old opt-out) and flips the column default so a
-- future stage insert that somehow bypasses the application layer still
-- lands on the safe value.

UPDATE workflow_stages SET requires_signature = true WHERE requires_signature = false;

ALTER TABLE workflow_stages ALTER COLUMN requires_signature SET DEFAULT true;
