package com.oak.dms.workflow.enums;

/**
 * workflow_supporting_documents.access_mode (V48). Always
 * {@code already_accessible} in Phase 2 - access is never granted or
 * bypassed (decision 18). {@code workflow_scoped_grant} is a future item.
 */
public enum SupportingDocumentAccessMode {
    already_accessible, workflow_scoped_grant
}
