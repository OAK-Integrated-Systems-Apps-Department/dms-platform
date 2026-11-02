-- 1.4 Digital Signature Integration - remembers, per task, which document
-- version signature placement started FROM, so placing more than once for
-- the SAME task (retrying after a step-up failure, going back and
-- re-placing, choosing a different signature image the second time...)
-- always replaces that one mark rather than stacking a new one on top of
-- the last attempt's own output. Without this, SignaturePlacementService
-- always stamped onto whatever version the instance CURRENTLY pointed to -
-- which, after a first placement, was already this same task's own
-- previous stamp, so a second placement stamped onto a page that already
-- had a mark on it, visibly showing both.
--
-- Set once, the first time this task places a signature; every later
-- placement for the same task reads and stamps from this same baseline
-- again (never from the version a previous attempt produced), then
-- re-advances the workflow instance's signing target to the new result -
-- replacing, not chaining, the task's own output.
ALTER TABLE workflow_tasks
    ADD COLUMN signature_placement_base_version_id UUID REFERENCES document_versions (version_id);
