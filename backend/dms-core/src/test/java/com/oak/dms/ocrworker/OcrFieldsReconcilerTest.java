package com.oak.dms.ocrworker;

import com.oak.dms.document.DocumentMetadataService;
import com.oak.dms.document.DocumentOcrFieldResult;
import com.oak.dms.document.DocumentOcrFieldResultRepository;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.security.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OcrFieldsReconciler}, driving the package-private
 * {@code reconcileOnce()} directly rather than the {@code @Scheduled}
 * wrapper -- nothing in this codebase has ever had a test that fires a
 * scheduled method, and the split exists precisely so this one does not
 * have to be the first.
 * <p>
 * The live counterpart is {@code OcrFieldsReconcilerIT}, which exercises
 * the real query and the real CAS against Postgres. These cover the
 * decisions: who gets claimed, who gets warned about, and that the tenant
 * binding is always released.
 */
@ExtendWith(MockitoExtension.class)
class OcrFieldsReconcilerTest {

    @Mock
    private DocumentVersionRepository documentVersionRepository;
    @Mock
    private DocumentOcrFieldResultRepository documentOcrFieldResultRepository;
    @Mock
    private DocumentMetadataService documentMetadataService;

    private final OcrProperties properties = new OcrProperties();

    private final UUID tenantId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private OcrFieldsReconciler reconciler() {
        return new OcrFieldsReconciler(
                documentVersionRepository, documentOcrFieldResultRepository,
                documentMetadataService, properties);
    }

    private DocumentVersion staleVersion() {
        return DocumentVersion.builder()
                .versionId(versionId)
                .documentId(documentId)
                .tenantId(tenantId)
                .storageObjectKey("k")
                .originalFilename("invoice.pdf")
                .mimeType("application/pdf")
                .ocrStatus("completed")
                .build();
    }

    private DocumentOcrFieldResult row(String key, String value, String status) {
        return DocumentOcrFieldResult.builder()
                .fieldResultId(UUID.randomUUID())
                .fieldKey(key)
                .value(value)
                .status(status)
                .confidence(BigDecimal.valueOf(0.9))
                .build();
    }

    private void givenStale(DocumentVersion... versions) {
        when(documentVersionRepository.findCompletedWithUnappliedOcrFields(any(), any(Pageable.class)))
                .thenReturn(List.of(versions));
    }

    // ------------------------------------------------------------------
    @Test
    void appliesFieldsAndStampsTheMarkerForAnUnappliedVersion() {
        givenStale(staleVersion());
        when(documentVersionRepository.markOcrFieldsAppliedIfUnset(eq(versionId), any())).thenReturn(1);
        when(documentOcrFieldResultRepository.findByDocumentIdAndTenantId(documentId, tenantId))
                .thenReturn(List.of(row("invoice_number", "INV-1", "extracted")));

        assertThat(reconciler().reconcileOnce()).isEqualTo(1);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(documentMetadataService).applyOcrFields(eq(documentId), captor.capture());
        assertThat(captor.getValue()).containsKey("invoice_number");
    }

    @Test
    void claimsBeforeApplying() {
        /* The claim is what stops a second instance also warning about this
         * version. Applying first and claiming afterwards would leave a
         * window in which both instances log about the same lost event. */
        givenStale(staleVersion());
        when(documentVersionRepository.markOcrFieldsAppliedIfUnset(eq(versionId), any())).thenReturn(0);

        assertThat(reconciler().reconcileOnce()).isZero();

        verify(documentMetadataService, never()).applyOcrFields(any(), any());
        verify(documentOcrFieldResultRepository, never()).findByDocumentIdAndTenantId(any(), any());
    }

    @Test
    void aVersionWhoseFieldsWereAllManuallyOverriddenIsStillMarkedApplied() {
        /* The case that makes the marker necessary rather than inferable.
         * applyOcrFields skips every field whose current value is
         * source='manual', so nothing is written to
         * document_metadata_values -- but the path DID run, and the
         * reconciler must not keep re-finding this document and warning
         * about it on every tick forever. */
        givenStale(staleVersion());
        when(documentVersionRepository.markOcrFieldsAppliedIfUnset(eq(versionId), any())).thenReturn(1);
        when(documentOcrFieldResultRepository.findByDocumentIdAndTenantId(documentId, tenantId))
                .thenReturn(List.of(row("invoice_number", "INV-1", "extracted")));

        assertThat(reconciler().reconcileOnce()).isEqualTo(1);

        // The marker is stamped by the claim, before applyOcrFields is even
        // called, so its own outcome cannot un-stamp it.
        verify(documentVersionRepository).markOcrFieldsAppliedIfUnset(eq(versionId), any());
    }

    @Test
    void skipsAVersionWithNothingApplicableToApply() {
        /* A version whose every field came back not_found has nothing to
         * write. The scan query already excludes it, but the mapper's own
         * skip rule is asserted here too so the two cannot disagree. */
        givenStale(staleVersion());
        when(documentVersionRepository.markOcrFieldsAppliedIfUnset(eq(versionId), any())).thenReturn(1);
        when(documentOcrFieldResultRepository.findByDocumentIdAndTenantId(documentId, tenantId))
                .thenReturn(List.of(row("invoice_number", null, "not_found")));

        assertThat(reconciler().reconcileOnce()).isEqualTo(1);
        verify(documentMetadataService, never()).applyOcrFields(any(), any());
    }

    @Test
    void bindsTheRowsOwnTenantAndAlwaysClearsIt() {
        /* The scheduler thread is long-lived and pooled. A leaked binding
         * would apply one tenant's context to the next row -- and this
         * system has no row-level security to catch that. */
        givenStale(staleVersion());
        when(documentVersionRepository.markOcrFieldsAppliedIfUnset(eq(versionId), any())).thenReturn(1);
        when(documentOcrFieldResultRepository.findByDocumentIdAndTenantId(documentId, tenantId))
                .thenReturn(List.of(row("invoice_number", "INV-1", "extracted")));

        doThrow(new IllegalStateException("stop here")).when(documentMetadataService)
                .applyOcrFields(any(), any());

        reconciler().reconcileOnce();

        // Cleared even though applyOcrFields threw.
        assertThatThrownBy(TenantContext::getTenantId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void oneFailingVersionDoesNotStopTheBatch() {
        DocumentVersion first = staleVersion();
        UUID secondVersionId = UUID.randomUUID();
        UUID secondDocumentId = UUID.randomUUID();
        DocumentVersion second = DocumentVersion.builder()
                .versionId(secondVersionId).documentId(secondDocumentId).tenantId(tenantId)
                .storageObjectKey("k").originalFilename("b.pdf").mimeType("application/pdf")
                .ocrStatus("completed").build();
        givenStale(first, second);

        when(documentVersionRepository.markOcrFieldsAppliedIfUnset(any(), any())).thenReturn(1);
        when(documentOcrFieldResultRepository.findByDocumentIdAndTenantId(documentId, tenantId))
                .thenThrow(new RuntimeException("boom"));
        when(documentOcrFieldResultRepository.findByDocumentIdAndTenantId(secondDocumentId, tenantId))
                .thenReturn(List.of(row("invoice_number", "INV-2", "extracted")));

        assertThat(reconciler().reconcileOnce()).isEqualTo(1);
        verify(documentMetadataService).applyOcrFields(eq(secondDocumentId), any());
    }

    @Test
    void doesNothingWhenNothingIsStale() {
        when(documentVersionRepository.findCompletedWithUnappliedOcrFields(any(), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(reconciler().reconcileOnce()).isZero();
        verify(documentVersionRepository, never()).markOcrFieldsAppliedIfUnset(any(), any());
    }

    @Test
    void theScheduledWrapperRespectsTheEnabledFlag() {
        properties.setReconcilerEnabled(false);
        reconciler().reconcile();
        verify(documentVersionRepository, never())
                .findCompletedWithUnappliedOcrFields(any(), any(Pageable.class));
    }

    @Test
    void theScheduledWrapperSwallowsAScanFailureSoTheTickSurvives() {
        /* A @Scheduled method that throws is not retried by some schedulers
         * and logs a bare stack trace in others. A DB blip must not end the
         * reconciler for the lifetime of the process. */
        when(documentVersionRepository.findCompletedWithUnappliedOcrFields(any(), any(Pageable.class)))
                .thenThrow(new RuntimeException("database is down"));

        reconciler().reconcile();  // must not throw
    }

    @Test
    void theStalenessCutoffIsDerivedFromTheConfiguredWindow() {
        properties.setStaleAfterMinutes(30);
        when(documentVersionRepository.findCompletedWithUnappliedOcrFields(any(), any(Pageable.class)))
                .thenReturn(List.of());

        OffsetDateTime before = OffsetDateTime.now().minusMinutes(30);
        reconciler().reconcileOnce();
        OffsetDateTime after = OffsetDateTime.now().minusMinutes(30);

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(documentVersionRepository)
                .findCompletedWithUnappliedOcrFields(cutoff.capture(), any(Pageable.class));
        assertThat(cutoff.getValue()).isBetween(before.minusSeconds(5), after.plusSeconds(5));
    }

    @Test
    void theBatchSizeBoundsTheScan() {
        properties.setBatchSize(7);
        when(documentVersionRepository.findCompletedWithUnappliedOcrFields(any(), any(Pageable.class)))
                .thenReturn(List.of());

        reconciler().reconcileOnce();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(documentVersionRepository)
                .findCompletedWithUnappliedOcrFields(any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(7);
    }
}
