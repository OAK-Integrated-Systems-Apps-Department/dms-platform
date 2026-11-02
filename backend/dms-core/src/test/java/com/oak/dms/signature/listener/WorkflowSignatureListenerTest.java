package com.oak.dms.signature.listener;

import com.oak.dms.audit.service.AuditService;
import com.oak.dms.signature.entity.DocumentSignature;
import com.oak.dms.signature.enums.SignatureStatus;
import com.oak.dms.signature.event.SignatureInvalidatedEvent;
import com.oak.dms.signature.repository.DocumentSignatureRepository;
import com.oak.dms.signature.service.DocumentSignatureLockService;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.enums.InstanceStatus;
import com.oak.dms.workflow.event.WorkflowOutcomeEvent;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowSignatureListenerTest {

    @Mock
    private WorkflowInstanceRepository instanceRepository;
    @Mock
    private DocumentSignatureRepository signatureRepository;
    @Mock
    private DocumentSignatureLockService lockService;
    @Mock
    private AuditService auditService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private WorkflowSignatureListener listener;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID instanceId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new WorkflowSignatureListener(instanceRepository, signatureRepository, lockService, auditService,
                eventPublisher);
        lenient().when(auditService.event(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new com.oak.dms.audit.helper.AuditEventBuilder(auditService, "x"));
    }

    private WorkflowInstance instance(InstanceStatus status, boolean versionChanged) {
        return WorkflowInstance.builder().instanceId(instanceId).tenantId(tenantId).documentId(documentId)
                .documentVersionId(versionId).status(status).versionChangedFlag(versionChanged)
                .initiatedBy(UUID.randomUUID()).build();
    }

    private DocumentSignature validSignature() {
        return DocumentSignature.builder().signatureId(UUID.randomUUID()).tenantId(tenantId).documentId(documentId)
                .versionId(versionId).workflowInstanceId(instanceId).status(SignatureStatus.valid).build();
    }

    // ----------------------------------------------------- cancel/reject invalidates

    @Test
    void cancelledInstanceInvalidatesItsValidSignatures() {
        when(instanceRepository.findByInstanceIdAndTenantIdAndDeletedFalse(instanceId, tenantId))
                .thenReturn(Optional.of(instance(InstanceStatus.cancelled, false)));
        DocumentSignature signature = validSignature();
        when(signatureRepository.findByTenantIdAndWorkflowInstanceIdAndStatus(tenantId, instanceId, SignatureStatus.valid))
                .thenReturn(List.of(signature));

        listener.onCancelledOrRejected(new WorkflowOutcomeEvent(tenantId, instanceId, UUID.randomUUID()));

        assertThat(signature.getStatus()).isEqualTo(SignatureStatus.invalidated);
        assertThat(signature.getStatusReason()).isNotBlank();
        assertThat(signature.getStatusChangedAt()).isNotNull();
        verify(signatureRepository).saveAll(List.of(signature));
        ArgumentCaptor<SignatureInvalidatedEvent> captor = ArgumentCaptor.forClass(SignatureInvalidatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().signatureIds()).containsExactly(signature.getSignatureId());
    }

    @Test
    void rejectedInstanceInvalidatesItsValidSignatures() {
        when(instanceRepository.findByInstanceIdAndTenantIdAndDeletedFalse(instanceId, tenantId))
                .thenReturn(Optional.of(instance(InstanceStatus.rejected, false)));
        DocumentSignature signature = validSignature();
        when(signatureRepository.findByTenantIdAndWorkflowInstanceIdAndStatus(tenantId, instanceId, SignatureStatus.valid))
                .thenReturn(List.of(signature));

        listener.onCancelledOrRejected(new WorkflowOutcomeEvent(tenantId, instanceId, UUID.randomUUID()));

        assertThat(signature.getStatus()).isEqualTo(SignatureStatus.invalidated);
        verify(signatureRepository).saveAll(List.of(signature));
    }

    @Test
    void approvedInstanceIsIgnoredByTheInvalidateListener() {
        when(instanceRepository.findByInstanceIdAndTenantIdAndDeletedFalse(instanceId, tenantId))
                .thenReturn(Optional.of(instance(InstanceStatus.approved, false)));

        listener.onCancelledOrRejected(new WorkflowOutcomeEvent(tenantId, instanceId, UUID.randomUUID()));

        verify(signatureRepository, never()).findByTenantIdAndWorkflowInstanceIdAndStatus(any(), any(), any());
        verify(signatureRepository, never()).saveAll(any());
    }

    @Test
    void noValidSignaturesMeansNoInvalidationOrEvent() {
        when(instanceRepository.findByInstanceIdAndTenantIdAndDeletedFalse(instanceId, tenantId))
                .thenReturn(Optional.of(instance(InstanceStatus.cancelled, false)));
        when(signatureRepository.findByTenantIdAndWorkflowInstanceIdAndStatus(tenantId, instanceId, SignatureStatus.valid))
                .thenReturn(List.of());

        listener.onCancelledOrRejected(new WorkflowOutcomeEvent(tenantId, instanceId, UUID.randomUUID()));

        verify(signatureRepository, never()).saveAll(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // --------------------------------------------------------------- approve locks

    @Test
    void approvedInstanceWithAValidSignatureLocksTheDocument() {
        when(instanceRepository.findByInstanceIdAndTenantIdAndDeletedFalse(instanceId, tenantId))
                .thenReturn(Optional.of(instance(InstanceStatus.approved, false)));
        when(signatureRepository.findByTenantIdAndWorkflowInstanceIdAndStatus(tenantId, instanceId, SignatureStatus.valid))
                .thenReturn(List.of(validSignature()));

        listener.onApproved(new WorkflowOutcomeEvent(tenantId, instanceId, UUID.randomUUID()));

        verify(lockService).lock(tenantId, documentId, versionId);
    }

    @Test
    void approvedInstanceWithoutAValidSignatureDoesNotLock() {
        when(instanceRepository.findByInstanceIdAndTenantIdAndDeletedFalse(instanceId, tenantId))
                .thenReturn(Optional.of(instance(InstanceStatus.approved, false)));
        when(signatureRepository.findByTenantIdAndWorkflowInstanceIdAndStatus(tenantId, instanceId, SignatureStatus.valid))
                .thenReturn(List.of());

        listener.onApproved(new WorkflowOutcomeEvent(tenantId, instanceId, UUID.randomUUID()));

        verify(lockService, never()).lock(any(), any(), any());
    }

    @Test
    void nonApprovedInstanceDoesNotLock() {
        when(instanceRepository.findByInstanceIdAndTenantIdAndDeletedFalse(instanceId, tenantId))
                .thenReturn(Optional.of(instance(InstanceStatus.cancelled, false)));

        listener.onApproved(new WorkflowOutcomeEvent(tenantId, instanceId, UUID.randomUUID()));

        verify(lockService, never()).lock(any(), any(), any());
    }
}
