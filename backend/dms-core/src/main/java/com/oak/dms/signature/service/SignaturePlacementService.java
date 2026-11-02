package com.oak.dms.signature.service;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.DocumentContentReader;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.scanning.dto.IngestionRequest;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.signature.dto.PlaceSignatureRequest;
import com.oak.dms.signature.dto.PlaceSignatureResponse;
import com.oak.dms.signature.dto.SignaturePlacementInfoResponse;
import com.oak.dms.signature.enums.VisualSignatureKind;
import com.oak.dms.signature.exception.SignatureException;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowTask;
import com.oak.dms.workflow.enums.TaskStatus;
import com.oak.dms.workflow.helper.TaskRouting;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Lets a signer place their visual signature on the actual document page -
 * a real, permanent mark burned into a new version ({@link DocumentStampService}),
 * as its own step strictly BEFORE sign-and-approve.
 * <p>
 * This can never be folded into {@code DocumentSignatureService#sign}/
 * {@code WorkflowTaskServiceImpl#actWithSignature}: that call already holds
 * pessimistic row locks (instance -> stage instance -> tasks, the documented
 * workflow lock order) for the whole of its transaction, and creating a
 * document version goes through the ingestion/scan gate, which {@code
 * IngestionGateImpl} explicitly "never runs inside a transaction" (each of
 * its steps commits on its own). Holding workflow locks across that would be
 * exactly the kind of long-held-lock deadlock risk the original spec's "never
 * take the document row lock inside the approval transaction" rule exists to
 * prevent.
 * <p>
 * Instead: resolve and authorize the task with a plain (lock-free) read via
 * {@link TaskRouting#canAct}, stamp the signer's mark onto this task's own
 * BASELINE version (see {@link WorkflowTask#getSignaturePlacementBaseVersionId()}'s
 * own doc comment - never just "whatever the instance currently targets", which
 * would be this same task's own previous attempt after a first placement),
 * submit the result through the real ingestion pipeline (so it is malware-
 * scanned and ENCRYPTED AT REST exactly like every other version -
 * {@code reason=signed_copy}), then advance {@code WorkflowInstance.documentVersionId}
 * to it ({@link WorkflowInstanceRepository#advanceDocumentVersion}). Sign-and-approve,
 * called right after by the frontend, reads that pointer completely unchanged
 * and so signs exactly the stamped version - no changes needed there. Multiple
 * DIFFERENT approvers on the same stage each stamp on top of whatever the
 * previous one left (their own baseline is captured as that already-stamped
 * version, the first time THEY place); re-placing within the SAME task
 * always replaces that one mark instead of stacking another on top of it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignaturePlacementService {

    private final WorkflowTaskRepository taskRepository;
    private final WorkflowStageInstanceRepository stageInstanceRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final TaskRouting taskRouting;
    private final DocumentVersionRepository versionRepository;
    private final DocumentContentReader contentReader;
    private final DocumentStampService stampService;
    private final TypedSignatureRenderer typedSignatureRenderer;
    private final UserVisualSignatureService visualSignatureService;
    private final IngestionGate ingestionGate;
    private final AuditService auditService;
    private final PlatformTransactionManager transactionManager;
    private final SignatureStampComposer stampComposer;
    private final com.oak.dms.user.UserRepository userRepository;
    private final com.oak.dms.department.repository.DepartmentRepository departmentRepository;

    public SignaturePlacementInfoResponse info(UUID taskId) {
        Resolved resolved = resolve(taskId);
        DownloadedDocument current = open(resolved, baseVersionId(resolved));
        boolean supported = stampService.supports(current.contentType());
        int pageCount = supported ? stampService.pageCount(readAll(current), current.contentType()) : 1;
        return new SignaturePlacementInfoResponse(resolved.instance.getDocumentId(),
                resolved.instance.getDocumentVersionId(), current.contentType(), supported, pageCount,
                isCurrentlyPlaced(resolved));
    }

    /**
     * Whether the instance is CURRENTLY targeting a stamped version, not merely whether this task has
     * ever pinned a baseline (which, once set, stays set forever - see {@link #remove}'s own doc
     * comment). Using the pinned-forever flag here was a bug: after a successful {@link #remove}, the
     * instance is back at its baseline, but the baseline itself is deliberately never cleared, so the
     * "already placed / Remove it" banner never went away - removing looked like it silently did
     * nothing, even though the version pointer really had been reverted.
     */
    private static boolean isCurrentlyPlaced(Resolved resolved) {
        UUID baseline = resolved.task.getSignaturePlacementBaseVersionId();
        return baseline != null && !baseline.equals(resolved.instance.getDocumentVersionId());
    }

    /**
     * Undoes this task's own placement - re-points the instance back at the baseline it stamped FROM
     * (never deleting the stamped version itself, just no longer signing it), so the signer can remove
     * a mark they decided they don't want after all. The baseline itself is left alone, so a later
     * re-placement for this same task still starts from the same clean original, not from nothing.
     *
     * @throws SignatureException 409 if this task has never placed a signature, or already reverted to
     *                             its baseline (nothing currently placed to remove)
     */
    public void remove(UUID taskId) {
        Resolved resolved = resolve(taskId);
        if (!isCurrentlyPlaced(resolved)) {
            throw SignatureException.conflict("SIGNATURE_PLACEMENT_NONE",
                    "This task hasn't placed a signature on the document yet.");
        }
        UUID baseline = resolved.task.getSignaturePlacementBaseVersionId();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                instanceRepository.advanceDocumentVersion(resolved.instance.getInstanceId(), resolved.tenantId,
                        baseline));
        auditService.event(AuditActionCatalog.DOCUMENT_SIGNATURE_PLACEMENT_REMOVED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(resolved.instance.getDocumentId())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("taskId", taskId, "revertedToVersionId", baseline))
                .log();
    }

    /** A JPEG (PDF) or the original bytes (an already-an-image document) for {@code page} (0-based). */
    public byte[] pagePreview(UUID taskId, int page, int width) {
        Resolved resolved = resolve(taskId);
        DownloadedDocument current = open(resolved, baseVersionId(resolved));
        if (!stampService.supports(current.contentType())) {
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_UNSUPPORTED_TYPE",
                    "Placing a signature on the page isn't supported for this file type.");
        }
        return stampService.renderPagePreview(readAll(current), current.contentType(), page, width);
    }

    public PlaceSignatureResponse place(UUID taskId, PlaceSignatureRequest request) {
        Resolved resolved = resolve(taskId);
        // Captured BEFORE stamping, and reused (never re-captured) on every later placement for this
        // same task - see WorkflowTask#getSignaturePlacementBaseVersionId's own doc comment.
        boolean firstPlacementForThisTask = resolved.task.getSignaturePlacementBaseVersionId() == null;
        UUID baseVersionId = baseVersionId(resolved);
        DownloadedDocument current = open(resolved, baseVersionId);
        String mimeType = current.contentType();
        if (!stampService.supports(mimeType)) {
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_UNSUPPORTED_TYPE",
                    "Placing a signature on the page isn't supported for this file type.");
        }
        byte[] sourceBytes = readAll(current);

        byte[] markPng = request.visualSignatureKind() == VisualSignatureKind.typed
                ? typedSignatureRenderer.render(visualSignatureService.validateTypedText(request.visualSignatureText()))
                : visualSignatureService.validateImage(request.visualSignatureImageBase64());
        // Cosmetic choice only (see PlaceSignatureRequest#includeSignerDetails's own doc comment) - the
        // bare mark is stamped as-is when the signer doesn't want the text block under it.
        byte[] composedMark = request.includeSignerDetails()
                ? stampComposer.compose(markPng, signerInfo(resolved.tenantId, TenantContext.getUserId()))
                : markPng;

        byte[] stamped = stampService.stamp(sourceBytes, mimeType, request.page(), request.xRatio(), request.yRatio(),
                request.widthRatio(), request.heightRatio(), composedMark);

        IngestionTicketResponse ticket = ingestionGate.submit(IngestionRequest.newVersion(
                resolved.instance.getDocumentId(), "signed_copy", current.filename(), stamped.length,
                new ByteArrayInputStream(stamped), "Signature placed on page " + (request.page() + 1)));

        if (ticket.status() != ScanResultStatus.clean || ticket.versionId() == null) {
            log.warn("Signed copy for task {} did not complete synchronously (status={})", taskId, ticket.status());
            throw SignatureException.unavailable("SIGNATURE_PLACEMENT_NOT_READY",
                    "Could not finish preparing the signed copy just now - please try again.");
        }

        // A @Modifying query needs its own active transaction to run at all (Spring Data refuses
        // otherwise, "No active transaction for update or delete query") - this method deliberately
        // has none of its own (see this class's own doc comment on why), so a short, dedicated one is
        // opened just for this one write, the same way IngestionGateImpl manages its own transactions
        // step by step rather than wrapping the whole non-transactional flow in one. Both writes below
        // share it: re-pointing the instance at the new stamp, and - only the very first time this task
        // places - pinning its baseline so every later re-placement keeps reading from it, not from the
        // stamp this call itself is about to produce.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            instanceRepository.advanceDocumentVersion(resolved.instance.getInstanceId(), resolved.tenantId,
                    ticket.versionId());
            if (firstPlacementForThisTask) {
                resolved.task.setSignaturePlacementBaseVersionId(baseVersionId);
                taskRepository.save(resolved.task);
            }
        });

        DocumentVersion saved = versionRepository
                .findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(ticket.versionId(),
                        resolved.instance.getDocumentId(), resolved.tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentVersion", ticket.versionId()));

        auditService.event(AuditActionCatalog.DOCUMENT_SIGNATURE_PLACED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(resolved.instance.getDocumentId())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("taskId", taskId, "versionId", saved.getVersionId(),
                        "versionNumber", saved.getVersionNumber(), "page", request.page()))
                .log();

        return new PlaceSignatureResponse(saved.getVersionId(), saved.getVersionNumber());
    }

    /** Name/position/department/now, for {@link SignatureStampComposer} - position/department are each omitted when unset, never shown blank. */
    private SignatureStampComposer.SignerInfo signerInfo(UUID tenantId, UUID userId) {
        com.oak.dms.user.User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        String department = user.getDepartmentId() == null ? null
                : departmentRepository.findByDepartmentIdAndTenantIdAndDeletedFalse(user.getDepartmentId(), tenantId)
                        .map(com.oak.dms.department.entity.Department::getName).orElse(null);
        return new SignatureStampComposer.SignerInfo(user.getDisplayName(), user.getPosition(), department,
                java.time.OffsetDateTime.now());
    }

    /** This task's pinned baseline if it's placed before, otherwise wherever the instance currently points (its first placement). */
    private static UUID baseVersionId(Resolved resolved) {
        UUID pinned = resolved.task.getSignaturePlacementBaseVersionId();
        return pinned != null ? pinned : resolved.instance.getDocumentVersionId();
    }

    private record Resolved(UUID tenantId, WorkflowInstance instance, WorkflowTask task) {
    }

    /** Plain (lock-free) reads only - see this class's own doc comment for why no row lock is taken here. */
    private Resolved resolve(UUID taskId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        WorkflowTask task = taskRepository.findByTaskIdAndTenantIdAndDeletedFalse(taskId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTask", taskId));
        if (!TaskStatus.DECIDABLE.contains(task.getStatus())) {
            throw SignatureException.conflict("WORKFLOW_TASK_CLOSED", "This task is no longer open.");
        }
        if (!taskRouting.canAct(tenantId, task, userId)) {
            throw SignatureException.forbidden("WORKFLOW_TASK_NOT_YOURS", "You are not able to act on this task.");
        }

        UUID stageInstanceId = taskRepository.findStageInstanceIdOfTask(taskId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTask", taskId));
        UUID instanceId = stageInstanceRepository.findInstanceIdOfStage(stageInstanceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowStageInstance", stageInstanceId));
        WorkflowInstance instance = instanceRepository.findByInstanceIdAndTenantIdAndDeletedFalse(instanceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", instanceId));
        return new Resolved(tenantId, instance, task);
    }

    private DownloadedDocument open(Resolved resolved, UUID versionId) {
        return contentReader.open(resolved.instance.getDocumentId(), versionId);
    }

    private static byte[] readAll(DownloadedDocument document) {
        try (var in = document.content()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not read the document for signature placement", e);
        }
    }
}
