package com.oak.dms.importexport.service;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.dto.CreateUploadSessionRequest;
import com.oak.dms.importexport.dto.UploadSessionResponse;
import com.oak.dms.importexport.entity.UploadSession;
import com.oak.dms.importexport.enums.UploadSessionStatus;
import com.oak.dms.importexport.exception.BulkImportException;
import com.oak.dms.importexport.repository.UploadSessionRepository;
import com.oak.dms.security.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Resumable upload: chunks in order, a resume point that survives a dropped chunk, one use per session. */
class UploadSessionServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final UploadSessionRepository repository = mock(UploadSessionRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ImportExportProperties properties = new ImportExportProperties();
    private UploadSessionService service;
    private UploadSession session;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT, USER);
        properties.setUploadSessionDir(dir.toString());
        properties.setMaxChunkSize(DataSize.ofBytes(4));
        properties.setMaxArchiveSize(DataSize.ofBytes(10));
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.saveAndFlush(any())).thenAnswer(call -> {
            session = call.getArgument(0);
            session.setUploadSessionId(UUID.randomUUID());
            return session;
        });
        when(repository.findByUploadSessionIdAndTenantIdAndInitiatedByAndDeletedFalse(any(), eq(TENANT), eq(USER)))
                .thenAnswer(call -> Optional.ofNullable(session)
                        .filter(s -> s.getUploadSessionId().equals(call.getArgument(0)) && !s.isDeleted()));
        when(repository.lockById(any())).thenAnswer(call -> Optional.ofNullable(session));
        service = new UploadSessionService(repository, properties, transactionManager);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void chunksInOrder_completeTheFile_andTheSessionIsUsedOnce() throws Exception {
        UUID id = service.create(new CreateUploadSessionRequest("big.zip", 6)).uploadSessionId();

        UploadSessionResponse first = service.appendChunk(id, 0, 4, bytes("ABCD"));
        assertThat(first.receivedBytes()).isEqualTo(4);
        assertThat(first.status()).isEqualTo(UploadSessionStatus.uploading);

        // A retried chunk at a stale offset is refused with where to resume.
        assertThatThrownBy(() -> service.appendChunk(id, 0, 4, bytes("ABCD")))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("Resume from 4");

        UploadSessionResponse last = service.appendChunk(id, 4, 2, bytes("EF"));
        assertThat(last.status()).isEqualTo(UploadSessionStatus.completed);

        Path file = service.takeCompletedFile(id);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("ABCDEF");
        assertThat(session.isDeleted()).isTrue();
        assertThatThrownBy(() -> service.takeCompletedFile(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aChunkThatArrivesShort_changesNothing_soItCanBeSentAgain() {
        UUID id = service.create(new CreateUploadSessionRequest("big.zip", 6)).uploadSessionId();

        assertThatThrownBy(() -> service.appendChunk(id, 0, 4, bytes("AB")))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("send it again from offset 0");
        assertThat(session.getReceivedBytes()).isZero();

        assertThat(service.appendChunk(id, 0, 4, bytes("ABCD")).receivedBytes()).isEqualTo(4);
    }

    @Test
    void limits_andAnUnfinishedUpload_areRefused() {
        assertThatThrownBy(() -> service.create(new CreateUploadSessionRequest("big.rar", 6)))
                .isInstanceOf(BulkImportException.class).hasMessageContaining(".zip");
        assertThatThrownBy(() -> service.create(new CreateUploadSessionRequest("big.zip", 11)))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("larger than");

        UUID id = service.create(new CreateUploadSessionRequest("big.zip", 6)).uploadSessionId();
        assertThatThrownBy(() -> service.appendChunk(id, 0, 5, bytes("ABCDE")))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("at most 4 bytes");
        service.appendChunk(id, 0, 4, bytes("ABCD"));
        assertThatThrownBy(() -> service.appendChunk(id, 4, 3, bytes("EFG")))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("past the file's declared size");
        assertThatThrownBy(() -> service.takeCompletedFile(id))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("4 of 6 bytes");

        session.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        assertThatThrownBy(() -> service.appendChunk(id, 4, 2, bytes("EF")))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("expired");
    }

    private static ByteArrayInputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
