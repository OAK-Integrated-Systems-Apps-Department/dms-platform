package com.oak.dms.watermark;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.bookmark.BookmarkPurgeHandler;
import com.oak.dms.checkout.LegalHoldService;
import com.oak.dms.common.exception.ApiException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentService;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.OcrArtifactWriter;
import com.oak.dms.document.RecycleBinService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.repository.StorageLocationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

/**
 * DOC-09: a purge and a restore (or a legal hold) of the same document must not overlap, and a purge must decide again,
 * under the document's row lock, what an earlier list or entity told it. Real PostgreSQL transactions on separate
 * threads and real (file system) storage; the interleavings are forced with latches inside the purge or the restore, and
 * "blocked" is observed in {@code pg_stat_activity} - nothing relies on sleeping. Failure cases are forced by making a
 * collaborator throw after the files were really deleted.
 */
@SpringBootTest
class RecycleBinPurgeConcurrencyIT extends WatermarkFixtures {

    @Autowired private RecycleBinService recycleBin;
    @Autowired private LegalHoldService legalHold;
    @Autowired private DocumentService documentService;
    @Autowired private StorageLocationRepository locations;
    @MockitoSpyBean private OcrArtifactWriter ocrWriter;
    @MockitoSpyBean private AuditService audit;
    @MockitoSpyBean private BookmarkPurgeHandler bookmarkPurge;
    @MockitoSpyBean private com.oak.dms.storage.service.ObjectStorageServiceFactory spiedStorage;

    private CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch proceed = new CountDownLatch(1);

    // ------------------------------------------------------------------------------------------ helpers

    /** The first purge to finish destroying a version's files stops there, inside its transaction, holding the lock. */
    private void pauseTheFirstPurgeAfterItsFilesAreGone() {
        AtomicBoolean armed = new AtomicBoolean(true);
        Mockito.doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (armed.compareAndSet(true, false)) {
                reached.countDown();
                assertThat(proceed.await(60, TimeUnit.SECONDS)).as("test released the purge").isTrue();
            }
            return result;
        }).when(ocrWriter).deleteFor(any());
    }

    /** The restore stops where it audits itself - after its lock and its changes, before it commits. */
    private void pauseTheRestoreBeforeItCommits() {
        AtomicBoolean armed = new AtomicBoolean(true);
        Mockito.doAnswer(invocation -> {
            if (AuditActionCatalog.DOCUMENT_RESTORED.equals(invocation.getArgument(0)) && armed.compareAndSet(true, false)) {
                reached.countDown();
                assertThat(proceed.await(60, TimeUnit.SECONDS)).as("test released the restore").isTrue();
            }
            return invocation.callRealMethod();
        }).when(audit).event(any());
    }

    private <T> CompletableFuture<T> onAnotherThread(Fixture f, Callable<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            TenantContext.set(f.tenantId(), f.userId());
            try {
                return work.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                TenantContext.clear();
            }
        });
    }

    private void awaitBlockedSession(CompletableFuture<?> blockedWork) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where datname = "
                    + "current_database() and pid <> pg_backend_pid() and wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            if (blockedWork.isDone()) {
                throw new AssertionError("the work finished without ever waiting on a lock");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        throw new AssertionError("no session became blocked on a lock");
    }

    private static Throwable causeOf(Throwable failure) {
        return failure instanceof ExecutionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    private void act(Fixture f) {
        TenantContext.clear();
        TenantContext.set(f.tenantId(), f.userId());
    }

    private void putInBin(Fixture f, String deletedAt) {
        jdbc.update("update documents set is_deleted = true, deleted_at = " + deletedAt + " where document_id = ?",
                f.documentId());
    }

    private Map<String, Object> row(Fixture f) {
        return jdbc.queryForMap("select is_deleted, purged_at is not null as purged, legal_hold from documents "
                + "where document_id = ?", f.documentId());
    }

    private boolean fileExists(UUID versionId) {
        Map<String, Object> v = jdbc.queryForMap("select storage_location_id, storage_object_key from "
                + "document_versions where version_id = ?", versionId);
        StorageLocation location = locations.findById((UUID) v.get("storage_location_id")).orElseThrow();
        return storage.resolve(location).exists(location, (String) v.get("storage_object_key"));
    }

    private boolean versionsPurged(Fixture f) {
        return jdbc.queryForObject("select count(*) from document_versions where document_id = ? and purged_at is null",
                Integer.class, f.documentId()) == 0;
    }

    private void assertIntactAndLive(Fixture f, UUID... versions) throws Exception {
        assertThat(row(f)).containsEntry("is_deleted", false).containsEntry("purged", false);
        for (UUID version : versions) {
            assertThat(fileExists(version)).as("file of " + version).isTrue();
        }
        act(f);
        try (var content = documentService.downloadDocument(f.documentId()).content()) {
            assertThat(content.readAllBytes()).as("still downloadable").isNotEmpty();
        }
    }

    private void assertPurged(Fixture f, UUID... versions) {
        assertThat(row(f)).containsEntry("is_deleted", true).containsEntry("purged", true);
        assertThat(versionsPurged(f)).isTrue();
        for (UUID version : versions) {
            assertThat(fileExists(version)).as("file of " + version).isFalse();
        }
    }

    private void assertInTheBinWithNothingMarked(Fixture f) {
        assertThat(row(f)).containsEntry("is_deleted", true).containsEntry("purged", false);
        assertThat(jdbc.queryForObject("select count(*) from document_versions where document_id = ? and "
                + "purged_at is not null", Integer.class, f.documentId())).as("no version marked purged").isZero();
        assertThat(auditCount(AuditActionCatalog.DOCUMENT_PURGED, f.documentId())).as("no purge recorded").isZero();
    }

    private static void assertCode(Throwable thrown, String code) {
        assertThat(causeOf(thrown)).isInstanceOf(ApiException.class);
        assertThat(((ApiException) causeOf(thrown)).getCode()).isEqualTo(code);
    }

    /** Another document of the same tenant, with its own file. */
    private Fixture anotherDocument(Fixture f, String title) {
        StorageLocation location = locations.findAll().stream().filter(l -> f.tenantId().equals(l.getTenantId())
                && l.getPurpose() == com.oak.dms.storage.enums.StoragePurpose.document).findFirst().orElseThrow();
        UUID typeId = documentRepository.findById(f.documentId()).orElseThrow().getDocumentTypeId();
        byte[] content = minimalPdf();
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String key = f.tenantId() + "/" + documentId + "/" + versionId;
        storage.resolve(location).upload(location, key, new ByteArrayInputStream(content), content.length, PDF_MIME);
        transactions.executeWithoutResult(status -> {
            seed(f, typeId, versionId, "other.pdf", sha256(content));
            versionRepository.save(version(f, documentId, versionId, 1, location, key, content, content.length));
            documentRepository.save(Document.builder().documentId(documentId).tenantId(f.tenantId())
                    .documentTypeId(typeId).title(title).currentVersionId(versionId).latestVersionId(versionId)
                    .ownerUserId(f.userId()).createdBy(f.userId()).build());
        });
        return new Fixture(f.tenantId(), f.userId(), f.username(), documentId, versionId, content.length);
    }

    /** A further version with a file of its own (the shared helper reuses the first version's object). */
    private UUID versionWithItsOwnFile(Fixture f, int number) {
        DocumentVersion first = versionRepository.findById(f.versionId()).orElseThrow();
        StorageLocation location = locations.findById(first.getStorageLocationId()).orElseThrow();
        UUID typeId = documentRepository.findById(f.documentId()).orElseThrow().getDocumentTypeId();
        byte[] content = (new String(minimalPdf(), StandardCharsets.ISO_8859_1) + "% v" + number)
                .getBytes(StandardCharsets.ISO_8859_1);
        UUID versionId = UUID.randomUUID();
        String key = f.tenantId() + "/" + f.documentId() + "/" + versionId;
        storage.resolve(location).upload(location, key, new ByteArrayInputStream(content), content.length, PDF_MIME);
        transactions.executeWithoutResult(status -> {
            seed(f, typeId, versionId, "v" + number + ".pdf", sha256(content));
            versionRepository.save(version(f, f.documentId(), versionId, number, location, key, content,
                    content.length));
        });
        return versionId;
    }

    private void seed(Fixture f, UUID typeId, UUID versionId, String filename, String fileHash) {
        UUID engineId = jdbc.queryForObject("select scan_engine_id from scan_engines where is_deleted = false "
                + "order by tenant_id nulls first limit 1", UUID.class);
        UUID staging = jdbc.queryForObject("select storage_location_id from storage_locations where tenant_id = ? "
                + "and purpose = 'staging' limit 1", UUID.class, f.tenantId());
        jdbc.update("""
                        insert into scan_results (scan_result_id, tenant_id, scan_engine_id, staging_location_id,
                            original_filename, mime_type, status, scan_mode, attempt_count, uploaded_by,
                            ingestion_kind, created_reason, document_type_id, title, document_version_id,
                            file_hash, engine_name, completed_at)
                        values (?, ?, ?, ?, ?, ?, 'clean', 'pre_store_stream', 1, ?, 'new_document',
                            'initial_upload', ?, ?, ?, ?, 'test-harness', now())
                        """,
                UUID.randomUUID(), f.tenantId(), engineId, staging, filename, PDF_MIME, f.userId(), typeId, "doc09",
                versionId, fileHash);
    }

    private DocumentVersion version(Fixture f, UUID documentId, UUID versionId, int number, StorageLocation location,
                                    String key, byte[] content, long size) {
        return DocumentVersion.builder().versionId(versionId).documentId(documentId).tenantId(f.tenantId())
                .versionNumber(number).majorVersion(1).minorVersion(number - 1)
                .storageLocationId(location.getStorageLocationId()).storageObjectKey(key)
                .originalFilename("doc09.pdf").mimeType(PDF_MIME).sizeBytes(size)
                .fileHash(sha256(content)).createdReason("initial_upload")
                .scanStatus(DocumentVersion.SCAN_STATUS_CLEAN).createdBy(f.userId()).build();
    }

    // ------------------------------------------------------------------------------------------ scenario A

    @Test
    void purgeStartsFirst_aConcurrentRestoreWaitsForTheLock_andIsThenRefused() throws Exception {
        Fixture f = fixture();
        putInBin(f, "now()");
        pauseTheFirstPurgeAfterItsFilesAreGone();

        CompletableFuture<Object> purging = onAnotherThread(f, () -> {
            recycleBin.purgeNow(f.documentId());
            return "purged";
        });
        assertThat(reached.await(60, TimeUnit.SECONDS)).isTrue();   // files destroyed, transaction open, lock held
        assertThat(fileExists(f.versionId())).isFalse();

        CompletableFuture<Object> restoring = onAnotherThread(f, () -> recycleBin.restore(f.documentId()));
        awaitBlockedSession(restoring);
        assertThat(restoring).as("the restore must wait for the purge's lock").isNotDone();
        assertThat(row(f)).as("nothing committed yet: still in the bin").containsEntry("is_deleted", true);

        proceed.countDown();
        assertThat(purging.get(60, TimeUnit.SECONDS)).isEqualTo("purged");
        assertThatThrownBy(() -> restoring.get(60, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ResourceNotFoundException.class);

        assertPurged(f, f.versionId());
        assertThat(auditCount(AuditActionCatalog.DOCUMENT_RESTORED, f.documentId())).isZero();
        assertThat(auditCount(AuditActionCatalog.DOCUMENT_PURGED, f.documentId())).isEqualTo(1);
        act(f);
        assertThatThrownBy(() -> documentService.getDocument(f.documentId())).isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------------------------------ scenario B

    @Test
    void restoreStartsFirst_aPurgeOfTheSameDocumentWaits_thenLeavesItAlone() throws Exception {
        Fixture f = fixture();
        putInBin(f, "now()");
        pauseTheRestoreBeforeItCommits();

        CompletableFuture<Object> restoring = onAnotherThread(f, () -> recycleBin.restore(f.documentId()));
        assertThat(reached.await(60, TimeUnit.SECONDS)).isTrue();   // restore holds the lock, not yet committed

        // a purge that was already decided on (the document is still in the bin as far as everyone can see)
        CompletableFuture<Object> purging = onAnotherThread(f, () -> {
            recycleBin.purgeNow(f.documentId());
            return "purged";
        });
        awaitBlockedSession(purging);
        assertThat(purging).as("the purge must wait for the restore's lock").isNotDone();
        assertThat(fileExists(f.versionId())).as("nothing destroyed while it waits").isTrue();

        proceed.countDown();
        restoring.get(60, TimeUnit.SECONDS);
        assertThatThrownBy(() -> purging.get(60, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        assertCode(causeOf(catchThrowable(purging)), "DOCUMENT_NOT_IN_RECYCLE_BIN");

        assertIntactAndLive(f, f.versionId());
        assertThat(auditCount(AuditActionCatalog.DOCUMENT_PURGED, f.documentId())).isZero();
        assertThat(auditCount(AuditActionCatalog.DOCUMENT_RESTORED, f.documentId())).isEqualTo(1);
    }

    private static Throwable catchThrowable(CompletableFuture<?> future) {
        try {
            future.get(60, TimeUnit.SECONDS);
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    // ------------------------------------------------------------------------------------------ scenario C

    @Test
    void aLegalHoldCannotBePlacedOnADocumentInTheBin_orOnAPurgedOne() throws Exception {
        Fixture f = fixture();
        putInBin(f, "now()");
        pauseTheFirstPurgeAfterItsFilesAreGone();
        CompletableFuture<Object> purging = onAnotherThread(f, () -> {
            recycleBin.purgeNow(f.documentId());
            return "purged";
        });
        assertThat(reached.await(60, TimeUnit.SECONDS)).isTrue();

        // while the purge is running (lock held, still in the bin): refused, no hold is created
        act(f);
        assertThatThrownBy(() -> legalHold.place(f.documentId(), "litigation")).isInstanceOf(ResourceNotFoundException.class);
        proceed.countDown();
        purging.get(60, TimeUnit.SECONDS);

        // after it: refused too
        assertThatThrownBy(() -> legalHold.place(f.documentId(), "litigation")).isInstanceOf(ResourceNotFoundException.class);
        assertThat(row(f)).containsEntry("legal_hold", false).containsEntry("purged", true);

        // a document damaged by the old race (live, yet purged) can't be held either
        Fixture damaged = anotherDocument(f, "damaged");
        jdbc.update("update documents set purged_at = now() where document_id = ?", damaged.documentId());
        assertThatThrownBy(() -> legalHold.place(damaged.documentId(), "litigation"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(row(damaged)).containsEntry("legal_hold", false);
    }

    @Test
    void aLegalHoldPlacedAfterARestore_stopsAPurgeThatWasDecidedBefore() throws Exception {
        Fixture f = fixture();
        Fixture first = anotherDocument(f, "first in the list");
        putInBin(first, "now()");
        putInBin(f, "now() - interval '1 hour'");
        pauseTheFirstPurgeAfterItsFilesAreGone();

        CompletableFuture<Object> emptying = onAnotherThread(f, recycleBin::emptyBin);
        assertThat(reached.await(60, TimeUnit.SECONDS)).isTrue();   // "first" purged up to its commit; f is on the list

        act(f);
        recycleBin.restore(f.documentId());                         // f comes back ...
        legalHold.place(f.documentId(), "litigation");              // ... and is put on hold (possible only when live)
        proceed.countDown();

        RecycleBinService.EmptyResult result = (RecycleBinService.EmptyResult) emptying.get(60, TimeUnit.SECONDS);
        assertThat(result.purged()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertPurged(first, first.versionId());
        assertThat(row(f)).containsEntry("is_deleted", false).containsEntry("purged", false)
                .containsEntry("legal_hold", true);
        assertThat(fileExists(f.versionId())).isTrue();
    }

    @Test
    void aHoldOrProtectionAppearingAfterTheListWasRead_isCaughtUnderTheLock() throws Exception {
        Fixture f = fixture();
        Fixture first = anotherDocument(f, "first in the list");
        Fixture held = anotherDocument(f, "held later");
        Fixture guarded = anotherDocument(f, "protected later");
        putInBin(first, "now()");
        putInBin(held, "now() - interval '1 hour'");
        putInBin(guarded, "now() - interval '2 hours'");
        pauseTheFirstPurgeAfterItsFilesAreGone();

        CompletableFuture<Object> emptying = onAnotherThread(f, recycleBin::emptyBin);
        assertThat(reached.await(60, TimeUnit.SECONDS)).isTrue();
        jdbc.update("update documents set legal_hold = true, legal_hold_at = now(), legal_hold_by = ?, "
                + "legal_hold_reason = 'x' where document_id = ?", f.userId(), held.documentId());
        jdbc.update("update document_versions set is_protected = true where document_id = ?", guarded.documentId());
        proceed.countDown();

        RecycleBinService.EmptyResult result = (RecycleBinService.EmptyResult) emptying.get(60, TimeUnit.SECONDS);
        assertThat(result.purged()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(2);
        assertPurged(first, first.versionId());
        assertThat(fileExists(held.versionId())).isTrue();
        assertThat(fileExists(guarded.versionId())).isTrue();
        assertThat(row(held)).containsEntry("purged", false);
        assertThat(row(guarded)).containsEntry("purged", false);
        // the document that was never in the bin is untouched too
        assertThat(fileExists(f.versionId())).isTrue();
    }

    // ------------------------------------------------------------------------------------------ scenario E

    @Test
    void emptyBin_skipsADocumentRestoredAfterItsListWasRead_andFinishesTheRest() throws Exception {
        Fixture f = fixture();
        Fixture first = anotherDocument(f, "first in the list");
        Fixture last = anotherDocument(f, "last in the list");
        putInBin(first, "now()");
        putInBin(f, "now() - interval '1 hour'");
        putInBin(last, "now() - interval '2 hours'");
        pauseTheFirstPurgeAfterItsFilesAreGone();

        CompletableFuture<Object> emptying = onAnotherThread(f, recycleBin::emptyBin);
        assertThat(reached.await(60, TimeUnit.SECONDS)).isTrue();
        act(f);
        recycleBin.restore(f.documentId());                         // restored while the bin is being emptied
        proceed.countDown();

        RecycleBinService.EmptyResult result = (RecycleBinService.EmptyResult) emptying.get(60, TimeUnit.SECONDS);
        assertThat(result.purged()).as("the others still went").isEqualTo(2);
        assertThat(result.skipped()).as("the restored one was skipped, not failed").isEqualTo(1);
        assertPurged(first, first.versionId());
        assertPurged(last, last.versionId());
        assertIntactAndLive(f, f.versionId());
        assertThat(auditCount(AuditActionCatalog.DOCUMENT_PURGED, f.documentId())).isZero();
    }

    @Test
    void theSweep_skipsADocumentRestoredAfterItWasSelected() throws Exception {
        Fixture f = fixture();
        Fixture first = anotherDocument(f, "oldest");
        putInBin(first, "now() - interval '40 days'");
        putInBin(f, "now() - interval '39 days'");
        pauseTheFirstPurgeAfterItsFilesAreGone();

        CompletableFuture<Object> sweeping = CompletableFuture.supplyAsync(() -> {
            recycleBin.purgeExpired();
            return "swept";
        });
        assertThat(reached.await(60, TimeUnit.SECONDS)).isTrue();
        act(f);
        recycleBin.restore(f.documentId());
        proceed.countDown();
        sweeping.get(60, TimeUnit.SECONDS);

        assertPurged(first, first.versionId());
        assertIntactAndLive(f, f.versionId());
        assertThat(auditCount(AuditActionCatalog.DOCUMENT_PURGED, f.documentId())).isZero();
    }

    @Test
    void theSweep_skipsADocumentThatWasRestoredAndDeletedAgain_itsRetentionPeriodStartedOver() throws Exception {
        Fixture f = fixture();
        Fixture first = anotherDocument(f, "oldest");
        putInBin(first, "now() - interval '40 days'");
        putInBin(f, "now() - interval '39 days'");
        pauseTheFirstPurgeAfterItsFilesAreGone();

        CompletableFuture<Object> sweeping = CompletableFuture.supplyAsync(() -> {
            recycleBin.purgeExpired();
            return "swept";
        });
        assertThat(reached.await(60, TimeUnit.SECONDS)).isTrue();
        act(f);
        recycleBin.restore(f.documentId());
        putInBin(f, "now()");                                       // deleted again, just now
        proceed.countDown();
        sweeping.get(60, TimeUnit.SECONDS);

        assertPurged(first, first.versionId());
        assertThat(row(f)).as("back in the bin with a fresh period, not purged").containsEntry("is_deleted", true)
                .containsEntry("purged", false);
        assertThat(fileExists(f.versionId())).isTrue();
    }

    // ------------------------------------------------------------------------------------------ scenario D

    @Test
    void aStorageFailureAfterSomeFilesAreGone_leavesTheDocumentInTheBin_refusesARestore_andARetryFinishes() throws Exception {
        Fixture f = fixture();
        UUID second = versionWithItsOwnFile(f, 2);
        putInBin(f, "now()");
        AtomicInteger calls = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            Object result = invocation.callRealMethod();            // this version's file really is deleted first
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("simulated storage failure after the first version's file went");
            }
            return result;
        }).when(ocrWriter).deleteFor(any());

        act(f);
        assertThatThrownBy(() -> recycleBin.purgeNow(f.documentId())).hasMessageContaining("simulated");
        assertInTheBinWithNothingMarked(f);
        assertThat(List.of(fileExists(f.versionId()), fileExists(second))).as("exactly one version's file is gone")
                .containsExactlyInAnyOrder(true, false);

        // a document in this state must not come back as an empty one
        assertThatThrownBy(() -> recycleBin.restore(f.documentId())).satisfies(e -> assertCode(e, "DOCUMENT_CONTENT_MISSING"));
        assertInTheBinWithNothingMarked(f);
        assertThat(auditCount(AuditActionCatalog.DOCUMENT_RESTORED, f.documentId())).isZero();

        Mockito.reset(ocrWriter);                                   // storage is back: the retry runs the same purge again
        recycleBin.purgeNow(f.documentId());
        assertPurged(f, f.versionId(), second);
        assertThat(auditCount(AuditActionCatalog.DOCUMENT_PURGED, f.documentId())).isEqualTo(1);
    }

    @Test
    void aFailureAfterTheFilesWereDeleted_rollsEverythingElseBack_andARetrySucceeds() throws Exception {
        Fixture f = fixture();
        putInBin(f, "now()");
        jdbc.update("insert into document_access (tenant_id, document_id, principal_type, principal_id, access_level) "
                + "values (?, ?, 'user', ?, 'view')", f.tenantId(), f.documentId(), f.userId());
        Integer accessRows = jdbc.queryForObject("select count(*) from document_access where document_id = ?",
                Integer.class, f.documentId());
        // the handler requires a transaction, so even stubbing it (through its proxy) has to happen inside one
        transactions.executeWithoutResult(status -> Mockito.doThrow(new IllegalStateException(
                "simulated database failure")).doCallRealMethod().when(bookmarkPurge).removeBookmarksOf(any(), any()));

        act(f);
        assertThatThrownBy(() -> recycleBin.purgeNow(f.documentId())).hasMessageContaining("simulated database failure");
        assertInTheBinWithNothingMarked(f);
        assertThat(fileExists(f.versionId())).as("the file really is gone - deletion is not transactional").isFalse();
        assertThat(jdbc.queryForObject("select count(*) from document_access where document_id = ?", Integer.class,
                f.documentId())).as("the access list was rolled back").isEqualTo(accessRows);
        assertThat(auditCount(AuditActionCatalog.DOCUMENT_PURGED, f.documentId())).isZero();
        assertThatThrownBy(() -> recycleBin.restore(f.documentId())).satisfies(e -> assertCode(e, "DOCUMENT_CONTENT_MISSING"));

        recycleBin.purgeNow(f.documentId());                        // deleting what is gone is a no-op
        assertPurged(f, f.versionId());
        assertThat(auditCount(AuditActionCatalog.DOCUMENT_PURGED, f.documentId())).isEqualTo(1);
    }

    @Test
    void aFailureBeforeAnyFileWasTouched_changesNothing_andTheDocumentCanStillBeRestored() throws Exception {
        Fixture f = fixture();
        putInBin(f, "now()");
        Mockito.doThrow(new IllegalStateException("simulated failure before anything was deleted")).doCallRealMethod()
                .when(spiedStorage).resolve(any());

        act(f);
        assertThatThrownBy(() -> recycleBin.purgeNow(f.documentId())).hasMessageContaining("before anything");
        assertInTheBinWithNothingMarked(f);
        assertThat(fileExists(f.versionId())).isTrue();

        recycleBin.restore(f.documentId());                         // nothing was lost, so it may come back
        assertIntactAndLive(f, f.versionId());
    }

    // ------------------------------------------------------------------------------------------ scenario F

    @Test
    void sequentialBehaviour_isUnchanged() throws Exception {
        Fixture f = fixture();
        putInBin(f, "now()");
        act(f);

        // restore, and back into the bin
        recycleBin.restore(f.documentId());
        assertIntactAndLive(f, f.versionId());
        assertThat(auditCount(AuditActionCatalog.DOCUMENT_RESTORED, f.documentId())).isEqualTo(1);
        putInBin(f, "now()");

        // a hold or a protected version refuses a manual purge
        jdbc.update("update documents set legal_hold = true, legal_hold_at = now(), legal_hold_by = ?, "
                + "legal_hold_reason = 'x' where document_id = ?", f.userId(), f.documentId());
        assertThatThrownBy(() -> recycleBin.purgeNow(f.documentId())).satisfies(e -> assertCode(e, "DOCUMENT_LEGAL_HOLD"));
        jdbc.update("update documents set legal_hold = false, legal_hold_at = null, legal_hold_by = null, "
                + "legal_hold_reason = null where document_id = ?", f.documentId());
        jdbc.update("update document_versions set is_protected = true where document_id = ?", f.documentId());
        assertThatThrownBy(() -> recycleBin.purgeNow(f.documentId()))
                .satisfies(e -> assertCode(e, "DOCUMENT_VERSION_PROTECTED"));
        assertThat(fileExists(f.versionId())).isTrue();
        jdbc.update("update document_versions set is_protected = false where document_id = ?", f.documentId());

        // an eligible one is purged, once
        recycleBin.purgeNow(f.documentId());
        assertPurged(f, f.versionId());
        assertThat(auditCount(AuditActionCatalog.DOCUMENT_PURGED, f.documentId())).isEqualTo(1);

        // and can't be restored or purged again
        assertThatThrownBy(() -> recycleBin.restore(f.documentId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> recycleBin.purgeNow(f.documentId())).isInstanceOf(ResourceNotFoundException.class);
        assertThat(auditCount(AuditActionCatalog.DOCUMENT_PURGED, f.documentId())).isEqualTo(1);
    }
}
