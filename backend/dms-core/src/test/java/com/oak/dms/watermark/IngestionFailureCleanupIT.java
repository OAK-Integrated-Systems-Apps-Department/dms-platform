package com.oak.dms.watermark;

import com.oak.dms.scanning.dto.IngestionRequest;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.repository.ScanResultRepository;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.scanning.service.impl.IngestionProcessor;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.exception.StorageDeleteException;
import com.oak.dms.storage.exception.StorageDownloadException;
import com.oak.dms.storage.exception.StorageException;
import com.oak.dms.storage.service.ObjectStorageService;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

/**
 * DOC-13 against the real ingestion gate, processor, PostgreSQL, file storage behind the encryption wrapper and
 * ClamAV: a failed copy leaves no object at its destination, a failed submission does not live on to be promoted,
 * and neither cleanup can take anything from a worker or a committed version.
 * <p>
 * Failures are injected by wrapping what {@link ObjectStorageServiceFactory#resolve} returns, at exact points
 * (an upload that dies after N bytes, a staged read that fails, a hook that runs a "worker" at a chosen moment),
 * so nothing depends on timing.
 */
@SpringBootTest
class IngestionFailureCleanupIT extends WatermarkFixtures {

    @Autowired private IngestionGate gate;
    @Autowired private IngestionProcessor processor;
    @Autowired private ScanResultRepository scanResults;
    @MockitoSpyBean private ObjectStorageServiceFactory factory;

    /** Upload to the document location dies after this many bytes (-1 = off); the partial bytes stay at the key. */
    private volatile int documentUploadFailsAfter = -1;
    /** Deleting an object in the document location fails (cleanup failure). */
    private final AtomicBoolean documentDeleteFails = new AtomicBoolean();
    /** The next download from staging runs this hook, then fails like a storage read error. */
    private volatile Runnable beforeFailingStagingRead;
    private final AtomicBoolean stagingUploadFailsUnchecked = new AtomicBoolean();
    /** Runs once at the moment the document-location upload fails, before the exception propagates. */
    private volatile Runnable onDocumentUploadFailure;

    private static final class FailingAfter extends InputStream {
        private final InputStream in;
        private long left;

        FailingAfter(InputStream in, long n) {
            this.in = in;
            this.left = n;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) {
                throw new IOException("simulated read failure");
            }
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) {
                left -= n;
            }
            return n;
        }
    }

    private void interceptStorage() {
        Mockito.doAnswer(call -> {
            ObjectStorageService real = (ObjectStorageService) call.callRealMethod();
            return Proxy.newProxyInstance(ObjectStorageService.class.getClassLoader(), new Class[]{ObjectStorageService.class},
                    (proxy, method, args) -> {
                        Object[] a = args;
                        if (args != null && args.length > 1 && args[0] instanceof StorageLocation location
                                && args[1] instanceof String key) {
                            boolean documents = location.getPurpose() == StoragePurpose.document;
                            boolean staging = location.getPurpose() == StoragePurpose.staging;
                            switch (method.getName()) {
                                case "upload" -> {
                                    if (documents && key.startsWith("documents/") && documentUploadFailsAfter >= 0) {
                                        a = args.clone();
                                        a[2] = new FailingAfter((InputStream) args[2], documentUploadFailsAfter);
                                        try {
                                            return method.invoke(real, a);
                                        } catch (InvocationTargetException e) {
                                            Runnable hook = onDocumentUploadFailure;
                                            if (hook != null) {
                                                onDocumentUploadFailure = null;
                                                hook.run();
                                            }
                                            throw e.getCause();
                                        }
                                    }
                                    if (staging && stagingUploadFailsUnchecked.get()) {
                                        a = args.clone();
                                        a[2] = new FailingAfter((InputStream) args[2], 100);
                                        try {
                                            return method.invoke(real, a);
                                        } catch (InvocationTargetException e) {
                                            if (e.getCause() instanceof StorageException) {
                                                throw new UncheckedIOException(new IOException("simulated unchecked failure"));
                                            }
                                            throw e.getCause();
                                        }
                                    }
                                }
                                case "download" -> {
                                    Runnable hook = beforeFailingStagingRead;
                                    if (staging && hook != null) {
                                        beforeFailingStagingRead = null;
                                        hook.run();
                                        throw new StorageDownloadException(key, new IOException("simulated read failure"));
                                    }
                                }
                                case "delete" -> {
                                    if (documents && documentDeleteFails.get()) {
                                        throw new StorageDeleteException(key, new IOException("simulated delete failure"));
                                    }
                                }
                                default -> { }
                            }
                        }
                        try {
                            return method.invoke(real, a);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }).when(factory).resolve(any());
    }

    private static byte[] pdf(String label) {
        return ("%PDF-1.4\n% " + label + " " + UUID.randomUUID() + "\n" + "x".repeat(6000)).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] zip() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("note.txt"));
            z.write(("hello " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return out.toByteArray();
    }

    private List<String> filesUnder(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString().replace('\\', '/')).sorted()
                    .collect(Collectors.toList());
        }
    }

    private IngestionTicketResponse submit(Fixture f, String filename, byte[] body) {
        TenantContext.set(f.tenantId(), f.userId());
        try {
            return gate.submit(IngestionRequest.newVersion(f.documentId(), "replacement_upload", filename, body.length,
                    new ByteArrayInputStream(body), "test"));
        } finally {
            TenantContext.clear();
        }
    }

    private Map<String, Object> scanRow(UUID tenantId) {
        return jdbc.queryForList("select scan_result_id, status, attempt_count, file_hash is not null as has_hash, "
                + "lease_expires_at is not null as leased, failure_reason, staging_purged_at is not null as purged, "
                + "document_version_id from scan_results where tenant_id = ? order by created_at desc limit 1", tenantId).get(0);
    }

    private int versions(UUID documentId) {
        return jdbc.queryForObject("select count(*) from document_versions where document_id = ?", Integer.class, documentId);
    }

    private List<String> versionKeys(UUID documentId) {
        return jdbc.queryForList("select storage_object_key from document_versions where document_id = ? order by version_number",
                String.class, documentId);
    }

    /** What the scheduler does once the row is due: re-claim it and run it. */
    private void runScheduler(Object scanResultId) {
        jdbc.update("update scan_results set next_attempt_at = now(), lease_expires_at = now() + interval '1 hour' where "
                + "scan_result_id = ?", scanResultId);
        processor.process((UUID) scanResultId);
    }

    /** Encryption key records of this tenant's document objects (their key starts with documents/{tenant}/). */
    private int documentKeyRecords(UUID tenantId) {
        return jdbc.queryForObject("select count(*) from storage_object_keys where object_key like ?",
                Integer.class, "documents/" + tenantId + "/%");
    }

    // ---- A / D: a copy that fails part way, through the real encryption wrapper ----

    @Test
    void aCopyThatFailsPartWayLeavesNoObjectOrKeyRecordAndTheRetryLeavesExactlyTheVersionsObjects() throws Exception {
        Fixture f = fixture();
        interceptStorage();
        Path documents = tempDir.resolve(f.tenantId().toString());
        byte[] body = pdf("one");
        int keyRecordsBefore = documentKeyRecords(f.tenantId());

        documentUploadFailsAfter = 100;
        submit(f, "memo.pdf", body);
        documentUploadFailsAfter = -1;
        Map<String, Object> row = scanRow(f.tenantId());

        // the failed attempt: retryable, nothing left at the destination, nothing recorded for it
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(row.get("attempt_count")).isEqualTo(1);
        assertThat(filesUnder(documents)).containsExactlyElementsOf(versionKeys(f.documentId()));
        assertThat(versions(f.documentId())).isEqualTo(1);
        assertThat(documentKeyRecords(f.tenantId())).as("no key record for the failed destination")
                .isEqualTo(keyRecordsBefore);

        runScheduler(row.get("scan_result_id"));

        assertThat(scanRow(f.tenantId()).get("status")).isEqualTo("clean");
        assertThat(versions(f.documentId())).isEqualTo(2);
        List<String> keys = versionKeys(f.documentId());
        assertThat(filesUnder(documents)).as("every object is referenced by a version").containsExactlyInAnyOrderElementsOf(keys);
        assertThat(documentKeyRecords(f.tenantId())).as("the promoted object is encrypted, with its key record")
                .isEqualTo(keyRecordsBefore + 1);

        // the committed content reads back, decrypted, as exactly what was uploaded
        StorageLocation location = documentLocation(f);
        try (InputStream in = factoryReal().resolve(location).download(location, keys.get(1)).content()) {
            assertThat(in.readAllBytes()).isEqualTo(body);
        }
    }

    // ---- B: gate failure after staging ----

    @Test
    void aStagingReadFailureAfterTheRowExistsFailsTheSubmissionAndTheSchedulerNeverPromotesIt() throws Exception {
        Fixture f = fixture();
        interceptStorage();
        Path staging = tempDir.resolve(f.tenantId() + "-staging");
        beforeFailingStagingRead = () -> { };

        assertThatThrownBy(() -> submit(f, "bundle.zip", zip())).isInstanceOf(StorageDownloadException.class);

        Map<String, Object> row = scanRow(f.tenantId());
        assertThat(row.get("status")).isEqualTo("failed");
        assertThat((String) row.get("failure_reason")).startsWith("Ingestion failed before it was accepted");
        assertThat(row.get("purged")).isEqualTo(true);
        assertThat(row.get("leased")).isEqualTo(false);
        assertThat(filesUnder(staging)).isEmpty();

        runScheduler(row.get("scan_result_id"));                       // the scheduler finds nothing to do
        assertThat(scanRow(f.tenantId()).get("status")).isEqualTo("failed");
        assertThat(versions(f.documentId())).isEqualTo(1);
    }

    // ---- C: unchecked staging failure; retention ----

    @Test
    void anUncheckedStagingFailureFailsTheRowAtOnceAndRemovesTheStagedObject() throws Exception {
        Fixture f = fixture();
        interceptStorage();
        Path staging = tempDir.resolve(f.tenantId() + "-staging");
        stagingUploadFailsUnchecked.set(true);

        assertThatThrownBy(() -> submit(f, "memo.pdf", pdf("three"))).isInstanceOf(UncheckedIOException.class);
        stagingUploadFailsUnchecked.set(false);

        Map<String, Object> row = scanRow(f.tenantId());
        assertThat(row.get("status")).isEqualTo("failed");
        assertThat(row.get("purged")).isEqualTo(true);
        assertThat(filesUnder(staging)).isEmpty();
        // nothing is left for the retention sweep either
        assertThat(scanResults.findStagingToPurge(Duration.ZERO, 100)).doesNotContain((UUID) row.get("scan_result_id"));
    }

    @Test
    void aFailedRowWhoseStagedObjectCouldNotBeRemovedStaysEligibleForTheRetentionSweep() throws Exception {
        // the existing safety net is unchanged: failed + not purged is picked up once the retention has passed
        Fixture f = fixture();
        interceptStorage();
        beforeFailingStagingRead = () -> { };
        assertThatThrownBy(() -> submit(f, "bundle.zip", zip())).isInstanceOf(StorageDownloadException.class);
        UUID id = (UUID) scanRow(f.tenantId()).get("scan_result_id");
        jdbc.update("update scan_results set staging_purged_at = null where scan_result_id = ?", id);   // as if the delete had failed

        assertThat(scanResults.findStagingToPurge(Duration.ofHours(72), 100)).doesNotContain(id);
        assertThat(scanResults.findStagingToPurge(Duration.ZERO, 100)).contains(id);
    }

    // ---- E: concurrency, deterministic ----

    @Test
    void aWorkerThatAlreadyPromotedTheRowIsNotUndoneByTheGatesLateFailure() throws Exception {
        Fixture f = fixture();
        interceptStorage();
        Path staging = tempDir.resolve(f.tenantId() + "-staging");
        // At the moment the gate's staged read fails, a worker has already taken the row over and promoted it.
        beforeFailingStagingRead = () -> {
            Object id = scanRow(f.tenantId()).get("scan_result_id");
            runScheduler(id);
        };

        assertThatThrownBy(() -> submit(f, "bundle.zip", zip())).isInstanceOf(StorageDownloadException.class);

        Map<String, Object> row = scanRow(f.tenantId());
        assertThat(row.get("status")).as("the worker's outcome stands").isEqualTo("clean");
        assertThat(versions(f.documentId())).as("exactly one new version, not two").isEqualTo(2);
        assertThat(row.get("document_version_id")).isNotNull();
        assertThat(filesUnder(tempDir.resolve(f.tenantId().toString())))
                .containsExactlyInAnyOrderElementsOf(versionKeys(f.documentId()));
        assertThat(filesUnder(staging)).isEmpty();
    }

    @Test
    void aWorkerThatHoldsTheLeaseKeepsItsRowAndItsStagedObjectWhenTheGateFails() throws Exception {
        Fixture f = fixture();
        interceptStorage();
        Path staging = tempDir.resolve(f.tenantId() + "-staging");
        // A worker has claimed the row (a new lease) but not finished when the gate's read fails.
        beforeFailingStagingRead = () -> jdbc.update("update scan_results set lease_expires_at = now() + interval '2 hours' "
                + "where tenant_id = ?", f.tenantId());

        assertThatThrownBy(() -> submit(f, "bundle.zip", zip())).isInstanceOf(StorageDownloadException.class);

        Map<String, Object> row = scanRow(f.tenantId());
        assertThat(row.get("status")).as("not forced to a terminal state").isEqualTo("pending");
        assertThat(row.get("leased")).isEqualTo(true);
        assertThat(filesUnder(staging)).as("the worker's content is untouched").hasSize(1);

        processor.process((UUID) row.get("scan_result_id"));            // the worker finishes
        assertThat(scanRow(f.tenantId()).get("status")).isEqualTo("clean");
        assertThat(versions(f.documentId())).isEqualTo(2);
    }

    @Test
    void aRetryThatSucceedsWhileTheFailedAttemptIsStillBeingCleanedUpKeepsItsOwnObject() throws Exception {
        Fixture f = fixture();
        interceptStorage();
        Path documents = tempDir.resolve(f.tenantId().toString());
        byte[] body = pdf("race");
        List<String> duringRetry = new ArrayList<>();
        documentUploadFailsAfter = 100;
        // Attempt A has failed (partial bytes written) and is about to throw; attempt B runs to completion first.
        onDocumentUploadFailure = () -> {
            documentUploadFailsAfter = -1;
            Object id = scanRow(f.tenantId()).get("scan_result_id");
            processor.process((UUID) id);
            try {
                duringRetry.addAll(filesUnder(documents));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };

        submit(f, "memo.pdf", body);
        documentUploadFailsAfter = -1;

        assertThat(scanRow(f.tenantId()).get("status")).isEqualTo("clean");
        List<String> keys = versionKeys(f.documentId());
        assertThat(keys).hasSize(2);
        assertThat(filesUnder(documents)).as("A's partial object is gone, B's is intact")
                .containsExactlyInAnyOrderElementsOf(keys);
        assertThat(duringRetry).as("A's partial object existed while B was promoted").hasSize(3);
        StorageLocation location = documentLocation(f);
        try (InputStream in = factoryReal().resolve(location).download(location, keys.get(1)).content()) {
            assertThat(in.readAllBytes()).isEqualTo(body);
        }
    }

    // ---- cleanup failure ----

    @Test
    void whenCleanupFailsTheOriginalFailureIsWhatIsRecordedTheOrphanIsReportedAndTheRetryStillPromotes() throws Exception {
        Fixture f = fixture();
        interceptStorage();
        Path documents = tempDir.resolve(f.tenantId().toString());
        byte[] body = pdf("two");
        documentUploadFailsAfter = 100;
        documentDeleteFails.set(true);

        submit(f, "memo.pdf", body);
        documentUploadFailsAfter = -1;
        documentDeleteFails.set(false);

        Map<String, Object> row = scanRow(f.tenantId());
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat((String) row.get("failure_reason")).contains("Failed to upload object").doesNotContain("delete");
        assertThat(filesUnder(documents)).as("the one object that could not be removed").hasSize(2);
        assertThat(versions(f.documentId())).isEqualTo(1);              // no committed version was touched

        runScheduler(row.get("scan_result_id"));

        assertThat(scanRow(f.tenantId()).get("status")).isEqualTo("clean");
        assertThat(versions(f.documentId())).isEqualTo(2);
        assertThat(filesUnder(documents)).as("the known residual orphan remains until a reconciliation sweep").hasSize(3);
    }

    // ---- helpers ----

    private StorageLocation documentLocation(Fixture f) {
        UUID id = jdbc.queryForObject("select storage_location_id from storage_locations where tenant_id = ? "
                + "and purpose = 'document'", UUID.class, f.tenantId());
        return storageLocationRepository.findById(id).orElseThrow();
    }

    /** The un-intercepted factory behaviour (the spy's real methods). */
    private ObjectStorageServiceFactory factoryReal() {
        Mockito.reset(factory);
        return factory;
    }
}
