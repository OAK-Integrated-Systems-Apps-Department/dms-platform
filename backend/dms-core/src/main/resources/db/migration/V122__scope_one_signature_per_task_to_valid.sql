-- Fixes a bug introduced by V119 (task retraction): uq_document_signatures_one_per_task
-- (V108) was scoped to "task_id IS NOT NULL" only, with no regard for status. Once a
-- task's signature was invalidated by DocumentSignatureService#invalidateForTaskRetraction
-- (status set to 'invalidated', the row itself kept - see V108's append-only trigger), the
-- old row still carried that task_id, so re-signing the same task after retraction always
-- hit this index and failed the insert - surfacing as the generic
-- "This task has already been signed" conflict (DocumentSignatureService#sign's
-- DataIntegrityViolationException catch) even though DocumentSignatureService#sign's own
-- existsByTenantIdAndTaskIdAndStatus(..., valid) pre-check had already decided a re-sign
-- was legitimate.
--
-- Narrowing the index to "status = 'valid'" keeps the original guarantee (never two VALID
-- signatures racing onto the same task - a concurrent duplicate still loses the insert) while
-- letting a task accumulate any number of invalidated rows behind one eventual valid one.
DROP INDEX uq_document_signatures_one_per_task;

CREATE UNIQUE INDEX uq_document_signatures_one_valid_per_task
    ON document_signatures (tenant_id, task_id) WHERE task_id IS NOT NULL AND status = 'valid';
