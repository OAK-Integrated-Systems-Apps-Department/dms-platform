package com.oak.dms.watermark.service;

import com.oak.dms.audit.service.AuditService;
import com.oak.dms.document.DocumentContentReader;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.adapter.FileSystemStorageAdapter;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.helper.StorageProperties;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.watermark.entity.WatermarkJob;
import com.oak.dms.watermark.enums.WatermarkJobStatus;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.repository.WatermarkJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DOC-20: {@code WatermarkJobService.download} reads a stored output key back only when it is the job's own - the new
 * {@code .../output} key or a legitimate legacy one - and otherwise answers as for an output that is gone, without
 * touching storage and without saying anything about the key. DB-free: the real service and the real filesystem adapter
 * over a temp directory; the repository, the access check and the audit are mocked.
 */
class WatermarkJobServiceDownloadGuardTest {

    @TempDir
    Path base;

    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final UUID job = UUID.randomUUID();
    private final UUID document = UUID.randomUUID();
    private final UUID version = UUID.randomUUID();

    private FileSystemStorageAdapter adapter;
    private ObjectStorageServiceFactory factory;
    private AuditService audit;
    private WatermarkJobService service;
    private WatermarkJob row;

    @BeforeEach
    void setUp() {
        TenantContext.set(tenant, user);
        adapter = spy(new FileSystemStorageAdapter(mock(StorageProperties.class)));
        factory = mock(ObjectStorageServiceFactory.class);
        when(factory.resolve(any())).thenReturn(adapter);
        StorageLocation location = StorageLocation.builder().storageLocationId(UUID.randomUUID()).tenantId(tenant)
                .name("staging").provider(StorageProvider.FILE_SYSTEM).purpose(StoragePurpose.staging)
                .basePath(base.toString()).build();
        StorageLocationRepository locations = mock(StorageLocationRepository.class);
        when(locations.findByStorageLocationIdAndDeletedFalse(location.getStorageLocationId())).thenReturn(Optional.of(location));

        row = new WatermarkJob();
        row.setJobId(job);
        row.setTenantId(tenant);
        row.setRequestedBy(user);
        row.setDocumentId(document);
        row.setVersionId(version);
        row.setStatus(WatermarkJobStatus.completed);
        row.setStorageLocationId(location.getStorageLocationId());
        row.setSourceMime("application/pdf");
        row.setExpiresAt(OffsetDateTime.now().plusHours(1));
        WatermarkJobRepository jobs = mock(WatermarkJobRepository.class);
        when(jobs.findByJobIdAndTenantIdAndRequestedBy(job, tenant, user)).thenReturn(Optional.of(row));

        DocumentContentReader reader = mock(DocumentContentReader.class);
        when(reader.describe(document, version)).thenReturn(new DocumentContentReader.VersionDetails(document, version, 1,
                "Title", "report.pdf", "application/pdf", 3));
        audit = mock(AuditService.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        service = new WatermarkJobService(jobs, mock(WatermarkService.class), reader, locations, factory, audit);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private String prefix() {
        return tenant + "/tmp/watermarks/" + job + "/";
    }

    private Path plant(String relative, String content) throws IOException {
        Path file = base.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    // ------------------------------------------------------------------ valid keys

    @Test
    void theNewKey_aLegacyKey_andANestedLegacyKey_areReadAsBefore() throws Exception {
        for (String name : new String[]{"output", "report.pdf", "a/b/c.pdf"}) {
            plant(prefix() + name, "PDF" + name.length());
            row.setOutputObjectKey(prefix() + name);
            row.setOutputSizeBytes((long) ("PDF" + name.length()).length());

            WatermarkJobService.DownloadedWatermark result = service.download(job);

            assertThat(result.content().readAllBytes()).as(name).isEqualTo(("PDF" + name.length()).getBytes(StandardCharsets.UTF_8));
            assertThat(result.contentType()).isEqualTo("application/pdf");
            assertThat(result.filename()).as("the name comes from the version, not the key").isEqualTo("report-watermarked.pdf");
            assertThat(result.sizeBytes()).isEqualTo(("PDF" + name.length()).length());
        }
        verify(audit, org.mockito.Mockito.times(3)).event(anyString());   // one download audit per successful read
    }

    // ------------------------------------------------------------------ invalid keys

    @Test
    void anUnsafeKey_isNeverReadFromStorage_andIsAnsweredAsAnOutputThatIsGone() throws Exception {
        UUID otherTenant = UUID.randomUUID();
        UUID otherJob = UUID.randomUUID();
        Path sibling = plant(tenant + "/tmp/watermarks/" + otherJob + "/their.pdf", "SIBLING SECRET");
        Path foreign = plant(otherTenant + "/tmp/watermarks/" + otherJob + "/their.pdf", "FOREIGN SECRET");
        String[] unsafe = {
                prefix() + "../" + otherJob + "/their.pdf",                               // parent traversal into a sibling job
                prefix() + "../../../" + otherTenant + "/tmp/watermarks/" + otherJob + "/their.pdf",
                prefix() + "..\\" + otherJob + "\\their.pdf",                             // Windows separators
                prefix() + "a/..\\../" + otherJob + "/their.pdf",                         // mixed separators
                base.relativize(sibling).toString().replace('\\', '/'),                   // another job, no traversal
                base.relativize(foreign).toString().replace('\\', '/'),                   // another tenant, no traversal
                "/" + base.relativize(foreign).toString().replace('\\', '/'),             // absolute
                foreign.toAbsolutePath().toString(),                                      // absolute filesystem path
                "", prefix(), prefix() + "a//b.pdf", prefix() + "a/", prefix() + "./x.pdf",
                prefix() + "x\u0000y.pdf", prefix() + "x\ny.pdf"};

        for (String key : unsafe) {
            row.setOutputObjectKey(key);

            assertThatThrownBy(() -> service.download(job)).as("refused: " + key.replace('\n', ' '))
                    .isInstanceOfSatisfying(WatermarkException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.GONE);
                        assertThat(e.getCode()).isEqualTo("WATERMARK_EXPIRED");
                        assertThat(e.getMessage()).doesNotContain("their").doesNotContain(otherJob.toString())
                                .doesNotContain(tenant.toString()).doesNotContain("..");
                    });
        }
        verify(adapter, never()).download(any(), anyString());
        verifyNoInteractions(audit);
        assertThat(Files.readString(sibling)).isEqualTo("SIBLING SECRET");
        assertThat(Files.readString(foreign)).isEqualTo("FOREIGN SECRET");
    }

    @Test
    void aNullKey_andAnExpiredOrUnavailableJob_keepTheirExistingAnswers() {
        row.setOutputObjectKey(null);
        assertThatThrownBy(() -> service.download(job)).isInstanceOfSatisfying(WatermarkException.class,
                e -> assertThat(e.getCode()).isEqualTo("WATERMARK_NOT_READY"));

        row.setOutputObjectKey(prefix() + "output");
        row.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        assertThatThrownBy(() -> service.download(job)).isInstanceOfSatisfying(WatermarkException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.GONE));
        verifyNoInteractions(factory);
    }
}
