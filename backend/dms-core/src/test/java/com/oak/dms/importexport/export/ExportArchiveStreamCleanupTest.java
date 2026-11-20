package com.oak.dms.importexport.export;

import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.DocumentService;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.folder.Folder;
import com.oak.dms.folder.FolderRepository;
import com.oak.dms.importexport.enums.TemplateFormat;
import com.oak.dms.metadata.entity.DocumentMetadata;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentMetadataRepository;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.repository.MetadataFieldRepository;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DOC-18: the storage stream a bulk export opens for a file is closed whatever goes wrong before or while it is copied
 * into the ZIP - the entry cannot be created (a write error; an over-long path), or the copy itself fails. DB-free: the
 * real {@link ExportArchiveBuilder} over mocked repositories and a tracked stream, with deterministic fault injection (no
 * disk is filled). {@code putNextEntry} used to run before the try-with-resources that owns the stream.
 */
class ExportArchiveStreamCleanupTest {

    
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID TYPE = UUID.randomUUID();

    static class Tracked extends ByteArrayInputStream {
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger opened = new AtomicInteger();

        Tracked() {
            super("PDF".getBytes());
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
    private final FolderRepository folders = mock(FolderRepository.class);
    private final DocumentTypeRepository types = mock(DocumentTypeRepository.class);
    private final DocumentMetadataRepository metadata = mock(DocumentMetadataRepository.class);
    private final MetadataFieldRepository fields = mock(MetadataFieldRepository.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final DocumentTypeFieldService schemas = mock(DocumentTypeFieldService.class);
    private final ExportArchiveBuilder builder = new ExportArchiveBuilder(documents, versions, folders, types, metadata,
            fields, documentService, schemas, mock(com.oak.dms.encryption.KeyPrefetcher.class),
            mock(com.oak.dms.watermark.service.WatermarkDefaultsService.class));

    @TempDir
    Path dir;

    private UUID document(UUID folderId, Tracked stream) {
        UUID id = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Document d = Document.builder().documentId(id).tenantId(TENANT).documentTypeId(TYPE).folderId(folderId)
                .title("Annual report").currentVersionId(versionId).latestVersionId(versionId)
                .ownerUserId(UUID.randomUUID()).build();
        when(documents.findAllByDocumentIdInAndTenantIdAndDeletedFalse(anyCollection(), eq(TENANT)))
                .thenAnswer(call -> ((Collection<?>) call.getArgument(0)).contains(id) ? List.of(d) : List.of());
        when(versions.findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId, id, TENANT))
                .thenReturn(Optional.of(DocumentVersion.builder().versionId(versionId).documentId(id).tenantId(TENANT)
                        .versionNumber(1).originalFilename("report.pdf").sizeBytes(3).build()));
        when(documentService.downloadDocumentFromJob(id, "bulk_export"))
                .thenAnswer(call -> new DownloadedDocument(stream, 3, "application/pdf", "report.pdf"));
        when(types.findAllById(any())).thenReturn(List.of(DocumentType.builder().documentTypeId(TYPE).tenantId(TENANT)
                .typeCode("INV").typeName("Invoice").build()));
        when(metadata.findById(any())).thenReturn(Optional.of(DocumentMetadata.builder().build()));
        return id;
    }

    private void expect(String what, Tracked stream) {
        assertThat(stream.closed.get()).as(what + ": the storage stream must be closed").isTrue();
    }

    // ---------------------------------------------------------------- control

    @Test
    void control_aNormalExport_closesTheStream() throws Exception {
        Tracked stream = new Tracked();
        UUID id = document(null, stream);

        builder.build(TENANT, List.of(id), null, true, TemplateFormat.csv, dir.resolve("ok.zip"), 1_000_000);

        assertThat(stream.closed.get()).isTrue();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(dir.resolve("ok.zip").toFile())) {
            assertThat(zip.stream().map(java.util.zip.ZipEntry::getName)).containsExactly("report.pdf", "metadata.csv");
            assertThat(new String(zip.getInputStream(zip.getEntry("report.pdf")).readAllBytes())).isEqualTo("PDF");
        }
    }

    // ---------------------------------------------------------------- putNextEntry fails: a write error (disk full)

    @Test
    void putNextEntryFailsWithAnIoError_theExportFails_andTheStorageStreamIsClosed() throws Exception {
        Tracked stream = new Tracked();
        UUID id = document(null, stream);
        Path target = dir.resolve("full.zip");
        OutputStream diskFull = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("No space left on device");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("No space left on device");
            }
        };
        try (MockedStatic<Files> files = Mockito.mockStatic(Files.class, invocation -> {
            if (invocation.getMethod().getName().equals("newOutputStream") && invocation.getArgument(0).equals(target)) {
                return diskFull;   // fails on the first write: the local header putNextEntry writes
            }
            return invocation.callRealMethod();
        })) {
            assertThatThrownBy(() -> builder.build(TENANT, List.of(id), null, true, TemplateFormat.csv, target, 1_000_000))
                    .isInstanceOf(java.io.UncheckedIOException.class).hasMessageContaining("Could not write the export ZIP")
                    .hasRootCauseMessage("No space left on device");
        }
        expect("export/putNextEntry-io-error", stream);
    }

    // ---------------------------------------------------------------- the copy itself fails

    @Test
    void aStorageStreamThatFailsWhileBeingCopied_failsTheExport_andIsClosed() throws Exception {
        Tracked stream = new Tracked() {
            @Override
            public int read() {
                throw new java.io.UncheckedIOException(new IOException("storage connection reset"));
            }

            @Override
            public int read(byte[] b, int off, int len) {
                throw new java.io.UncheckedIOException(new IOException("storage connection reset"));
            }

            @Override
            public long transferTo(OutputStream out) {      // ByteArrayInputStream copies without read(); the real one reads
                throw new java.io.UncheckedIOException(new IOException("storage connection reset"));
            }
        };
        UUID id = document(null, stream);

        assertThatThrownBy(() -> builder.build(TENANT, List.of(id), null, true, TemplateFormat.csv,
                dir.resolve("reset.zip"), 1_000_000)).isInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("storage connection reset");
        expect("export/copy-fails", stream);
    }

    // ---------------------------------------------------------------- the entry itself is rejected (name too long)

    @Test
    void anOverLongEntryName_failsTheExport_andTheStorageStreamIsClosed() throws Exception {
        // 450 nested folders, 150 characters each: the entry path exceeds ZIP's 65,535-byte limit.
        List<Folder> chain = new ArrayList<>();
        UUID parent = null;
        UUID leaf = null;
        for (int i = 0; i < 450; i++) {
            UUID id = UUID.randomUUID();
            chain.add(Folder.builder().folderId(id).tenantId(TENANT).parentFolderId(parent)
                    .folderName(String.valueOf(i % 10).repeat(150)).build());
            parent = id;
            leaf = id;
        }
        UUID root = chain.get(0).getFolderId();
        when(folders.findByTenantIdAndDeletedFalseOrderByFolderNameAsc(TENANT)).thenReturn(chain);
        Tracked stream = new Tracked();
        UUID id = document(leaf, stream);

        assertThatThrownBy(() -> builder.build(TENANT, List.of(id), root, true, TemplateFormat.csv,
                dir.resolve("deep.zip"), 1_000_000)).isInstanceOf(RuntimeException.class);
        expect("export/over-long-entry-name", stream);
    }
}
