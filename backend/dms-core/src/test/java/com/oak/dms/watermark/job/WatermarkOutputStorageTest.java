package com.oak.dms.watermark.job;

import com.oak.dms.audit.service.AuditService;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.storage.adapter.FileSystemStorageAdapter;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.exception.StorageDeleteException;
import com.oak.dms.storage.helper.StorageProperties;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.service.StorageLocationResolver;
import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.entity.WatermarkJob;
import com.oak.dms.watermark.enums.WatermarkJobStatus;
import com.oak.dms.watermark.repository.WatermarkJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DOC-20, DB-free, with the REAL {@code WatermarkJobWorker.store}, {@code dropPartialOutput} and
 * {@code WatermarkOutputSweeper.deleteObject/purge} (private, called by reflection) over the REAL
 * {@link FileSystemStorageAdapter} on a disposable temp directory; only the collaborators around them are mocked.
 */
class WatermarkOutputStorageTest {

    @TempDir
    Path base;

    private final UUID tenant = UUID.randomUUID();
    private final byte[] output = "WATERMARKED".getBytes(StandardCharsets.UTF_8);

    private FileSystemStorageAdapter adapter;
    private StorageLocation location;
    private ObjectStorageServiceFactory factory;
    private PlatformTransactionManager manager;

    @BeforeEach
    void setUp() {
        adapter = spy(new FileSystemStorageAdapter(mock(StorageProperties.class)));
        location = StorageLocation.builder().storageLocationId(UUID.randomUUID()).tenantId(tenant).name("staging")
                .provider(StorageProvider.FILE_SYSTEM).purpose(StoragePurpose.staging).basePath(base.toString()).build();
        factory = mock(ObjectStorageServiceFactory.class);
        when(factory.resolve(any())).thenReturn(adapter);
        manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus(true));
    }

    private WatermarkJob newJob() {
        WatermarkJob job = new WatermarkJob();
        job.setJobId(UUID.randomUUID());
        job.setTenantId(tenant);
        job.setStorageLocationId(location.getStorageLocationId());
        return job;
    }

    private WatermarkJobWorker worker(WatermarkJob job) {
        StorageLocationResolver resolver = mock(StorageLocationResolver.class);
        when(resolver.resolveForTenant(tenant, StoragePurpose.staging)).thenReturn(location);
        WatermarkJobRepository repository = mock(WatermarkJobRepository.class);
        when(repository.lockById(job.getJobId())).thenReturn(Optional.of(job));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        WatermarkJobWorker worker = mock(WatermarkJobWorker.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(worker, "storageLocationResolver", resolver);
        ReflectionTestUtils.setField(worker, "storage", factory);
        ReflectionTestUtils.setField(worker, "repository", repository);
        ReflectionTestUtils.setField(worker, "transactions", new TransactionTemplate(manager));
        ReflectionTestUtils.setField(worker, "properties", new WatermarkProperties());
        ReflectionTestUtils.setField(worker, "auditService", mock(AuditService.class, Mockito.RETURNS_DEEP_STUBS));
        return worker;
    }

    private WatermarkOutputSweeper sweeper(WatermarkJob job) {
        StorageLocationRepository locations = mock(StorageLocationRepository.class);
        when(locations.findByStorageLocationIdAndDeletedFalse(location.getStorageLocationId())).thenReturn(Optional.of(location));
        WatermarkJobRepository repository = mock(WatermarkJobRepository.class);
        when(repository.lockById(job.getJobId())).thenReturn(Optional.of(job));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        WatermarkOutputSweeper sweeper = mock(WatermarkOutputSweeper.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(sweeper, "storageLocationRepository", locations);
        ReflectionTestUtils.setField(sweeper, "storage", factory);
        ReflectionTestUtils.setField(sweeper, "repository", repository);
        ReflectionTestUtils.setField(sweeper, "transactions", new TransactionTemplate(manager));
        return sweeper;
    }

    /** Runs the real store; returns the key the job recorded. */
    private String store(WatermarkJob job, String originalFilename) {
        DownloadedDocument source = new DownloadedDocument(new ByteArrayInputStream(new byte[0]), 0, "application/pdf",
                originalFilename);
        ReflectionTestUtils.invokeMethod(worker(job), "store", job, output, source);
        return job.getOutputObjectKey();
    }

    private Path jobDir(WatermarkJob job) {
        return base.resolve(WatermarkOutputKeys.namespacePrefix(tenant, job.getJobId())).normalize();
    }

    private long filesInBase() throws IOException {
        try (Stream<Path> files = Files.walk(base)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private Path plant(String relative, String content) throws IOException {
        Path file = base.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    // ------------------------------------------------------------------ A. key construction

    @Test
    void whateverTheOriginalFilename_theKeyIsTheJobsOwnOutput_andNothingElseIsTouched() throws Exception {
        UUID otherTenant = UUID.randomUUID();
        UUID otherJob = UUID.randomUUID();
        String victimRelative = otherTenant + "/tmp/watermarks/" + otherJob + "/their-report.pdf";
        Path victim = plant(victimRelative, "VICTIM ORIGINAL");
        Path sibling = plant(tenant + "/tmp/x.pdf", "SIBLING ORIGINAL");
        long before = filesInBase();

        String[] names = {"report.pdf", "a/b/c.pdf", "../../x.pdf", "..\\..\\x.pdf",
                "../../../../" + otherTenant + "/tmp/watermarks/" + otherJob + "/their-report.pdf",
                "x\u0007y.pdf", "n".repeat(300) + ".pdf", "ගොනුව.pdf", null};
        for (String name : names) {
            WatermarkJob job = newJob();
            String key = store(job, name);

            String label = name == null ? "null" : (name.length() > 40 ? "long" : name);
            assertThat(key).as(label).isEqualTo(tenant + "/tmp/watermarks/" + job.getJobId() + "/output");
            if (name != null && name.length() > 3) {
                assertThat(key).as(label + ": no filename in the key").doesNotContain(name.substring(0, 3) + name.substring(3));
            }
            assertThat(base.resolve(key).normalize()).as(label).startsWith(jobDir(job));
            assertThat(adapter.download(location, key).content().readAllBytes()).as(label + ": readable").isEqualTo(output);
            assertThat(Files.readString(victim)).as(label + ": the other tenant's object is intact").isEqualTo("VICTIM ORIGINAL");
            assertThat(Files.readString(sibling)).as(label).isEqualTo("SIBLING ORIGINAL");
        }
        assertThat(filesInBase() - before).as("one new file per job, all inside job directories").isEqualTo(names.length);
    }

    @Test
    void theKeyForAJobIsTheSame_whateverTheFilename() {
        WatermarkJob job = newJob();

        String first = store(job, "report.pdf");
        String second = store(job, "../../x.pdf");

        assertThat(second).isEqualTo(first);
    }

    // ------------------------------------------------------------------ B. cleanup - the worker (a failed job)

    @Test
    void workerCleanup_deletesNewAndLegitimateLegacyKeys() throws Exception {
        WatermarkJob job = newJob();
        for (String name : new String[]{"output", "report.pdf", "a/b/c.pdf"}) {
            plant(WatermarkOutputKeys.namespacePrefix(tenant, job.getJobId()) + name, "OUT");
            job.setOutputObjectKey(WatermarkOutputKeys.namespacePrefix(tenant, job.getJobId()) + name);

            Object cleaned = ReflectionTestUtils.invokeMethod(worker(job), "dropPartialOutput", job);

            assertThat(cleaned).as(name).isEqualTo(true);
            assertThat(Files.exists(base.resolve(job.getOutputObjectKey()))).as(name + " deleted").isFalse();
        }
    }

    @Test
    void workerCleanup_neverDeletesOutsideTheJobNamespace() throws Exception {
        WatermarkJob job = newJob();
        UUID otherTenant = UUID.randomUUID();
        Path victim = plant(otherTenant + "/tmp/watermarks/" + UUID.randomUUID() + "/their.pdf", "VICTIM");
        Path sibling = plant(tenant + "/tmp/x.pdf", "SIBLING");
        String prefix = WatermarkOutputKeys.namespacePrefix(tenant, job.getJobId());
        String[] unsafe = {prefix + "../../x.pdf",
                prefix + "../../../../" + base.relativize(victim).toString().replace('\\', '/'),
                base.relativize(victim).toString().replace('\\', '/'),            // another tenant's key, no traversal at all
                "/" + base.relativize(victim).toString().replace('\\', '/'),     // absolute
                victim.toAbsolutePath().toString(), prefix + "..\\..\\x.pdf", prefix + "a//b.pdf", prefix};

        for (String key : unsafe) {
            job.setOutputObjectKey(key);

            Object cleaned = ReflectionTestUtils.invokeMethod(worker(job), "dropPartialOutput", job);

            assertThat(cleaned).as("refused: " + key).isEqualTo(false);
        }
        verify(adapter, never()).delete(any(), anyString());
        assertThat(Files.readString(victim)).isEqualTo("VICTIM");
        assertThat(Files.readString(sibling)).isEqualTo("SIBLING");
    }

    @Test
    void workerCleanup_aMissingKeyOrFailure_isHandledWithoutDeletingAnythingElse() throws Exception {
        WatermarkJob none = newJob();
        assertThat((Object) ReflectionTestUtils.invokeMethod(worker(none), "dropPartialOutput", none)).isEqualTo(true);

        WatermarkJob failing = newJob();
        failing.setOutputObjectKey(WatermarkOutputKeys.keyFor(tenant, failing.getJobId()));
        Mockito.doThrow(new StorageDeleteException(failing.getOutputObjectKey(), new IOException("disk"))).when(adapter)
                .delete(any(), anyString());
        assertThat((Object) ReflectionTestUtils.invokeMethod(worker(failing), "dropPartialOutput", failing))
                .as("a failure is logged and swallowed, as before").isEqualTo(true);
    }

    // ------------------------------------------------------------------ C. cleanup - the sweeper

    @Test
    void sweeper_deletesNewAndLegacyOutputs_andClearsTheKey() throws Exception {
        for (String name : new String[]{"output", "report.pdf", "a/b/c.pdf"}) {
            WatermarkJob job = newJob();
            String key = WatermarkOutputKeys.namespacePrefix(tenant, job.getJobId()) + name;
            plant(key, "OUT");
            job.setOutputObjectKey(key);
            job.setStatus(WatermarkJobStatus.completed);

            Object removed = ReflectionTestUtils.invokeMethod(sweeper(job), "purge", List.of(job), "expired");

            assertThat(removed).as(name).isEqualTo(1);
            assertThat(Files.exists(base.resolve(key))).as(name + " deleted").isFalse();
            assertThat(job.getOutputObjectKey()).as(name + ": pointer cleared").isNull();
            assertThat(job.getStatus()).isEqualTo(WatermarkJobStatus.expired);
            assertThat(job.getPurgedAt()).isNotNull();
        }
    }

    @Test
    void sweeper_refusesAnUnsafeKey_deletesNothing_andKeepsTheKeyOnTheRowForReview() throws Exception {
        UUID otherTenant = UUID.randomUUID();
        Path victim = plant(otherTenant + "/tmp/watermarks/" + UUID.randomUUID() + "/their.pdf", "VICTIM");
        Path sibling = plant(tenant + "/tmp/x.pdf", "SIBLING");
        WatermarkJob job = newJob();
        String prefix = WatermarkOutputKeys.namespacePrefix(tenant, job.getJobId());
        String[] unsafe = {prefix + "../../x.pdf",
                prefix + "../../../../" + base.relativize(victim).toString().replace('\\', '/'),
                base.relativize(victim).toString().replace('\\', '/'), "/etc/x.pdf"};

        for (String key : unsafe) {
            job.setOutputObjectKey(key);
            job.setStatus(WatermarkJobStatus.completed);
            job.setPurgedAt(null);

            ReflectionTestUtils.invokeMethod(sweeper(job), "purge", List.of(job), "expired");

            assertThat(job.getOutputObjectKey()).as("kept for investigation: " + key).isEqualTo(key);
            assertThat(job.getStatus()).as("no longer downloadable").isEqualTo(WatermarkJobStatus.expired);
            assertThat(job.getPurgedAt()).isNotNull();
        }
        verify(adapter, never()).delete(any(), anyString());
        assertThat(Files.readString(victim)).isEqualTo("VICTIM");
        assertThat(Files.readString(sibling)).isEqualTo("SIBLING");
    }

    @Test
    void sweeper_aMissingKey_andAFailedDelete_behaveAsBefore() {
        WatermarkJob none = newJob();
        none.setStatus(WatermarkJobStatus.completed);
        assertThat((Object) ReflectionTestUtils.invokeMethod(sweeper(none), "deleteObject", none)).isEqualTo(true);

        WatermarkJob failing = newJob();
        failing.setOutputObjectKey(WatermarkOutputKeys.keyFor(tenant, failing.getJobId()));
        failing.setStatus(WatermarkJobStatus.completed);
        Mockito.doThrow(new StorageDeleteException(failing.getOutputObjectKey(), new IOException("disk"))).when(adapter)
                .delete(any(), anyString());
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(sweeper(failing), "deleteObject", failing))
                .isInstanceOf(StorageDeleteException.class);
        Object removed = ReflectionTestUtils.invokeMethod(sweeper(failing), "purge", List.of(failing), "expired");
        assertThat(removed).as("the job is not counted as swept, and its row is not updated").isEqualTo(0);
        assertThat(failing.getOutputObjectKey()).isNotNull();
        assertThat(failing.getStatus()).isEqualTo(WatermarkJobStatus.completed);
    }
}
