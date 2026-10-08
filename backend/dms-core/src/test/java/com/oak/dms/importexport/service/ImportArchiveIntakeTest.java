package com.oak.dms.importexport.service;

import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.dto.BulkImportStatusResponse;
import com.oak.dms.importexport.dto.ImportArchiveReceivedResponse;
import com.oak.dms.importexport.dto.ImportFileReceivedResponse;
import com.oak.dms.importexport.exception.BulkImportException;
import com.oak.dms.importexport.exception.ImportBatchStateException;
import com.oak.dms.importexport.exception.ImportFileAlreadyReceivedException;
import com.oak.dms.importexport.exception.ImportFileNotInBatchException;
import com.oak.dms.scanning.exception.FileTypeNotAllowedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A ZIP's entries go through receiveFile one by one; a bad entry is skipped and reported, never fatal. */
class ImportArchiveIntakeTest {

    private static final UUID BATCH = UUID.randomUUID();

    private final BulkImportService bulkImportService = mock(BulkImportService.class);
    private final UploadSessionService uploadSessionService = mock(UploadSessionService.class);
    private final ImportExportProperties properties = new ImportExportProperties();
    private final ImportArchiveIntake intake = new ImportArchiveIntake(bulkImportService, uploadSessionService, properties);
    private final List<String> received = new ArrayList<>();

    @TempDir
    Path dir;

    @BeforeEach
    void uploadingBatch() {
        when(bulkImportService.getStatus(BATCH)).thenReturn(status("uploading", 1));
        when(bulkImportService.receiveFile(eq(BATCH), any(), any(), anyLong(), any())).thenAnswer(call -> {
            String name = call.getArgument(1);
            try (InputStream in = call.getArgument(4)) {
                received.add(name + "=" + new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            return switch (name) {
                case "unlisted.pdf" -> throw new ImportFileNotInBatchException("not listed");
                case "dup.pdf" -> throw new ImportFileAlreadyReceivedException("already");
                case "evil.exe" -> throw new FileTypeNotAllowedException("This file type is not allowed.");
                default -> new ImportFileReceivedResponse(1, received.size(), 3);
            };
        });
    }

    @Test
    void unpack_stagesEachFileByItsBaseName_skippingFoldersAndMacMetadata() throws Exception {
        Path zip = zip("2026/jan/a.pdf", "A", "b.pdf", "B", "unlisted.pdf", "U", "dup.pdf", "D", "evil.exe", "E",
                "__MACOSX/2026/._a.pdf", "junk", "2026/.DS_Store", "junk", "2026/jan/", null);

        ImportArchiveReceivedResponse result = intake.unpack(BATCH, zip);

        assertThat(received).containsExactly("a.pdf=A", "b.pdf=B", "unlisted.pdf=U", "dup.pdf=D", "evil.exe=E");
        assertThat(result.entriesRead()).isEqualTo(5);
        assertThat(result.filesMatched()).isEqualTo(2);
        assertThat(result.problemCount()).isEqualTo(3);
        assertThat(result.problems()).extracting(ImportArchiveReceivedResponse.EntryProblem::message).containsExactly(
                "Not listed in the template - skipped.",
                "Already uploaded for every row that lists it - skipped.",
                "This file type is not allowed.");
    }

    @Test
    void unpack_batchSubmittedMeanwhile_stops() throws Exception {
        when(bulkImportService.receiveFile(eq(BATCH), eq("a.pdf"), any(), anyLong(), any()))
                .thenThrow(new ImportBatchStateException("submitted"));

        assertThatThrownBy(() -> intake.unpack(BATCH, zip("a.pdf", "A", "b.pdf", "B")))
                .isInstanceOf(ImportBatchStateException.class);
    }

    @Test
    void notAZip_tooManyEntries_orBatchNotUploading_isRefusedBeforeStagingAnything() throws Exception {
        Path notZip = Files.writeString(dir.resolve("x.zip"), "definitely not a zip");
        assertThatThrownBy(() -> intake.unpack(BATCH, notZip))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("isn't a valid ZIP");

        properties.setMaxArchiveEntries(1);
        assertThatThrownBy(() -> intake.unpack(BATCH, zip("a.pdf", "A", "b.pdf", "B")))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("more than 1 entries");

        properties.setMaxArchiveEntries(100);
        when(bulkImportService.getStatus(BATCH)).thenReturn(status("queued", 1));
        assertThatThrownBy(() -> intake.unpack(BATCH, zip("a.pdf", "A")))
                .isInstanceOf(ImportBatchStateException.class);
        assertThatThrownBy(() -> intake.receiveFromSession(BATCH, UUID.randomUUID()))
                .isInstanceOf(ImportBatchStateException.class);
        verify(uploadSessionService, never()).takeCompletedFile(any()); // session not used up
        verify(bulkImportService, never()).receiveFile(any(), any(), any(), anyLong(), any());
    }

    private Path zip(String... namesAndContents) throws Exception {
        Path target = Files.createTempFile(dir, "import", ".zip");
        try (OutputStream out = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndContents[i]));
                if (namesAndContents[i + 1] != null) {
                    zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        }
        return target;
    }

    private static BulkImportStatusResponse status(String status, int filesReceived) {
        return new BulkImportStatusResponse(BATCH, UUID.randomUUID(), status, 3, filesReceived, 0, 0, List.of(),
                OffsetDateTime.now());
    }
}
