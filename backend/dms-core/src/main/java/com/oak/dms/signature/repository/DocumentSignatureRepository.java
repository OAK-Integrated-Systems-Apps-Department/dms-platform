package com.oak.dms.signature.repository;

import com.oak.dms.signature.entity.DocumentSignature;
import com.oak.dms.signature.enums.SignatureStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentSignatureRepository extends JpaRepository<DocumentSignature, UUID> {

    List<DocumentSignature> findByTenantIdAndDocumentIdOrderBySignedAtDesc(UUID tenantId, UUID documentId);

    List<DocumentSignature> findByTenantIdAndWorkflowInstanceId(UUID tenantId, UUID workflowInstanceId);

    List<DocumentSignature> findByTenantIdAndWorkflowInstanceIdAndStatus(UUID tenantId, UUID workflowInstanceId,
                                                                         SignatureStatus status);

    Optional<DocumentSignature> findByTenantIdAndSignatureId(UUID tenantId, UUID signatureId);

    boolean existsByTenantIdAndTaskId(UUID tenantId, UUID taskId);

    Optional<DocumentSignature> findByTenantIdAndTaskId(UUID tenantId, UUID taskId);

    /** One row per document ({@code SignatureLockReconcileJob}) whose workflow approved with a valid signature but whose document isn't locked yet - the after-commit lock step can fail, this is the safety net. */
    interface PendingLock {
        UUID getTenantId();
        UUID getDocumentId();
        UUID getWorkflowInstanceId();
        UUID getVersionId();
    }

    @Query(value = """
            SELECT DISTINCT s.tenant_id AS tenantId, s.document_id AS documentId,
                   s.workflow_instance_id AS workflowInstanceId, s.version_id AS versionId
            FROM document_signatures s
            JOIN workflow_instances i ON i.instance_id = s.workflow_instance_id
            JOIN documents d ON d.document_id = s.document_id
            WHERE s.status = 'valid' AND i.status = 'approved' AND d.signature_locked_at IS NULL
            LIMIT :limit
            """, nativeQuery = true)
    List<PendingLock> findPendingLocks(@Param("limit") int limit);
}
