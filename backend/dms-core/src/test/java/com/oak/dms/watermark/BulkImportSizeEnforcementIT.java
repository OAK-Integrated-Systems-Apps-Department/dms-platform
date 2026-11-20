package com.oak.dms.watermark;

import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.dto.ImportArchiveReceivedResponse;
import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.exception.BulkImportException;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.service.BulkImportService;
import com.oak.dms.importexport.service.ImportArchiveIntake;
import com.oak.dms.importexport.service.UploadSessionService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DOC-10 on real infrastructure: PostgreSQL, real file-system staging storage behind the default encryption wrapper,
 * real ZIP parsing, the real intake service and the real virus scan. A ZIP entry that says it holds 100 bytes but holds
 * 8 MiB must not get past a 1 MiB batch cap - the original finding - and what is staged must be judged, counted and
 * recorded by its real size. External storage (S3-compatible, Azure) is not exercised here.
 */
@SpringBootTest
class BulkImportSizeEnforcementIT extends WatermarkFixtures {

    private static final int MIB = 1024 * 1024;

    @Autowired private ImportArchiveIntake intake;
    @Autowired private BulkImportService bulk;
    @Autowired private ImportExportProperties props;
    @Autowired private ImportBatchRepository batches;
    @Autowired private ImportItemRepository items;
    @MockitoSpyBean private UploadSessionService uploadSessions;

    private DataSize capBefore;

    @AfterEach
    void restoreTheCap() {
        if (capBefore != null) {
            props.setMaxBatchSize(capBefore);
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private void cap(long bytes) {
        capBefore = props.getMaxBatchSize();
        props.setMaxBatchSize(DataSize.ofBytes(bytes));
    }

    /** A ZIP of deflated PDF-looking entries; a declared size >= 0 is written into that entry's central directory header. */
    private static byte[] zip(Map<String, long[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                int real = (int) entry.getValue()[0];
                byte[] data = new byte[real];
                byte[] head = "%PDF-1.4\n".getBytes(StandardCharsets.ISO_8859_1);
                System.arraycopy(head, 0, data, 0, head.length);
                z.putNextEntry(new ZipEntry(entry.getKey()));
                z.write(data);
                z.closeEntry();
            }
        }
        byte[] bytes = out.toByteArray();
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = bytes.length - 22;
        while (buf.getInt(eocd) != 0x06054b50) {
            eocd--;
        }
        int count = buf.getShort(eocd + 10) & 0xffff;
        int pos = buf.getInt(eocd + 16);
        var declared = entries.values().iterator();
        for (int i = 0; i < count; i++) {
            assertThat(buf.getInt(pos)).isEqualTo(0x02014b50);
            long forged = declared.next()[1];
            if (forged >= 0) {
                buf.putInt(pos + 24, (int) forged);        // uncompressed size, as the uploader would write it
            }
            pos += 46 + (buf.getShort(pos + 28) & 0xffff) + (buf.getShort(pos + 30) & 0xffff)
                    + (buf.getShort(pos + 32) & 0xffff);
        }
        return bytes;
    }

    /** name -> {real bytes, declared bytes or -1 for honest}. */
    private static Map<String, long[]> entries(Object... nameRealDeclared) {
        Map<String, long[]> map = new LinkedHashMap<>();
        for (int i = 0; i < nameRealDeclared.length; i += 3) {
            map.put((String) nameRealDeclared[i],
                    new long[]{((Number) nameRealDeclared[i + 1]).longValue(), ((Number) nameRealDeclared[i + 2]).longValue()});
        }
        return map;
    }

    private UUID batchWith(Fixture f, String... filenames) {
        UUID typeId = documentRepository.findById(f.documentId()).orElseThrow().getDocumentTypeId();
        jdbc.update("insert into document_type_schemas (tenant_id, document_type_id, schema_version) values (?, ?, 1) "
                + "on conflict do nothing", f.tenantId(), typeId);
        ImportBatch batch = batches.save(ImportBatch.builder().tenantId(f.tenantId())
                .sourceType(ImportBatch.SourceType.bulk_import).documentTypeId(typeId).schemaVersion(1)
                .status(ImportBatch.ImportBatchStatus.uploading).totalRecords(filenames.length)
                .correlationId(UUID.randomUUID()).duplicateAction("keep_both").createdBy(f.userId()).build());
        int row = 2;
        for (String name : filenames) {
            items.save(ImportItem.builder().tenantId(f.tenantId()).importBatchId(batch.getImportBatchId())
                    .rowNumber(row++).filenameReference(name).rowValues("{\"title\":\"" + name + "\",\"fields\":{}}")
                    .status(ImportItem.ImportItemStatus.pending).build());
        }
        return batch.getImportBatchId();
    }

    private ImportItem item(UUID batch, String filename) {
        return items.findAllByBatchAndFilename(batch, filename).get(0);
    }

    private ImportArchiveReceivedResponse receiveZip(UUID batch, byte[] zip) {
        return intake.receive(batch, new MockMultipartFile("archive", "files.zip", "application/zip", zip));
    }

    private long filesUnder(Fixture f) throws IOException {
        Path staging = tempDir.resolve(f.tenantId() + "-staging");
        if (!Files.exists(staging)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(staging)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    private long keyRows(Fixture f) {
        return jdbc.queryForObject("select count(*) from storage_object_keys where tenant_id = ?", Long.class,
                f.tenantId());
    }

    private long bytesHeld(ImportItem item) throws IOException {
        StorageLocation location = storageLocationRepository.findById(item.getStagedStorageLocationId()).orElseThrow();
        try (InputStream in = storage.resolve(location).download(location, item.getStagedObjectKey()).content()) {
            return in.transferTo(java.io.OutputStream.nullOutputStream());
        }
    }

    private void assertRejected(ImportArchiveReceivedResponse response, UUID batch, Fixture f, String filename,
                                String messagePart) throws IOException {
        assertThat(response.filesMatched()).isZero();
        assertThat(response.problems()).hasSize(1);
        assertThat(response.problems().get(0).message()).contains(messagePart);
        ImportItem item = item(batch, filename);
        assertThat(item.getStagedObjectKey()).as("no staged item").isNull();
        assertThat(item.getStagedSizeBytes()).isNull();
    }

    // ------------------------------------------------------------------------------------------------ the original finding

    @Test
    void anEntryDeclaring100BytesButHolding8MiB_isRejected_leavesNothingBehind_andTheBudgetIsIntact() throws Exception {
        Fixture f = fixture();
        cap(MIB);
        TenantContext.set(f.tenantId(), f.userId());
        UUID batch = batchWith(f, "big.pdf", "small.pdf");
        long filesBefore = filesUnder(f);
        long keysBefore = keyRows(f);                 // the fixture's own seeded file already has one

        ImportArchiveReceivedResponse forged = receiveZip(batch, zip(entries("big.pdf", 8 * MIB, 100)));

        assertRejected(forged, batch, f, "big.pdf", "not the size its header says");
        assertThat(filesUnder(f)).as("no oversized object remains in storage").isEqualTo(filesBefore);
        assertThat(keyRows(f)).as("and no encryption key row for it").isEqualTo(keysBefore);
        assertThat(items.sumStagedBytes(batch)).as("the batch total is untouched").isZero();

        // the next honest file is judged against the true total, and is staged with its real size
        ImportArchiveReceivedResponse honest = receiveZip(batch, zip(entries("small.pdf", 300 * 1024, -1)));
        assertThat(honest.filesMatched()).isEqualTo(1);
        ImportItem small = item(batch, "small.pdf");
        assertThat(small.getStagedSizeBytes()).isEqualTo(300L * 1024);
        assertThat(bytesHeld(small)).as("what storage holds is what was recorded").isEqualTo(300L * 1024);
        assertThat(items.sumStagedBytes(batch)).isEqualTo(300L * 1024);
        assertThat(filesUnder(f) - filesBefore).isEqualTo(1);
    }

    @Test
    void anHonestZipBelowTheLimit_isStagedWithItsRealSize() throws Exception {
        Fixture f = fixture();
        cap(MIB);
        TenantContext.set(f.tenantId(), f.userId());
        UUID batch = batchWith(f, "a.pdf");
        long keysBefore = keyRows(f);

        ImportArchiveReceivedResponse response = receiveZip(batch, zip(entries("a.pdf", 500 * 1024, -1)));

        assertThat(response.filesMatched()).isEqualTo(1);
        assertThat(response.problems()).isEmpty();
        ImportItem a = item(batch, "a.pdf");
        assertThat(a.getStagedSizeBytes()).isEqualTo(500L * 1024);
        assertThat(bytesHeld(a)).isEqualTo(500L * 1024);
        assertThat(keyRows(f) - keysBefore).as("through the encryption wrapper").isEqualTo(1);
    }

    @Test
    void anHonestZipAboveTheLimit_isRefusedByTheBudget_andNothingIsStored() throws Exception {
        Fixture f = fixture();
        cap(MIB);
        TenantContext.set(f.tenantId(), f.userId());
        UUID batch = batchWith(f, "big.pdf");
        long filesBefore = filesUnder(f);

        ImportArchiveReceivedResponse response = receiveZip(batch, zip(entries("big.pdf", 8 * MIB, -1)));

        assertRejected(response, batch, f, "big.pdf", "over its 1MB limit");
        assertThat(filesUnder(f)).isEqualTo(filesBefore);
        assertThat(items.sumStagedBytes(batch)).isZero();
    }

    @Test
    void severalEntriesWhoseRealSizesTogetherExceedTheCap_areStagedUntilTheBudgetIsUsed() throws Exception {
        Fixture f = fixture();
        cap(MIB);
        TenantContext.set(f.tenantId(), f.userId());
        UUID batch = batchWith(f, "1.pdf", "2.pdf", "3.pdf");

        ImportArchiveReceivedResponse response = receiveZip(batch,
                zip(entries("1.pdf", 400 * 1024, -1, "2.pdf", 400 * 1024, -1, "3.pdf", 400 * 1024, -1)));

        assertThat(response.filesMatched()).isEqualTo(2);
        assertThat(response.problems()).hasSize(1);
        assertThat(response.problems().get(0).entry()).isEqualTo("3.pdf");
        assertThat(items.sumStagedBytes(batch)).isEqualTo(800L * 1024);
        assertThat(item(batch, "3.pdf").getStagedObjectKey()).isNull();
    }

    @Test
    void severalForgedEntries_cannotTogetherExceedTheCap() throws Exception {
        Fixture f = fixture();
        cap(MIB);
        TenantContext.set(f.tenantId(), f.userId());
        UUID batch = batchWith(f, "1.pdf", "2.pdf", "3.pdf");
        long filesBefore = filesUnder(f);

        ImportArchiveReceivedResponse response = receiveZip(batch,
                zip(entries("1.pdf", 600 * 1024, 10, "2.pdf", 600 * 1024, 10, "3.pdf", 600 * 1024, 10)));

        assertThat(response.filesMatched()).isZero();
        assertThat(response.problems()).hasSize(3);
        assertThat(items.sumStagedBytes(batch)).isZero();
        assertThat(filesUnder(f)).isEqualTo(filesBefore);
    }

    @Test
    void aRejectedEntry_doesNotDisturbTheOnesStagedBeforeIt_orAfterIt() throws Exception {
        Fixture f = fixture();
        cap(MIB);
        TenantContext.set(f.tenantId(), f.userId());
        UUID batch = batchWith(f, "ok1.pdf", "forged.pdf", "ok2.pdf");

        ImportArchiveReceivedResponse response = receiveZip(batch,
                zip(entries("ok1.pdf", 100 * 1024, -1, "forged.pdf", 4 * MIB, 50, "ok2.pdf", 100 * 1024, -1)));

        assertThat(response.filesMatched()).isEqualTo(2);
        assertThat(response.problems()).hasSize(1);
        assertThat(response.problems().get(0).entry()).isEqualTo("forged.pdf");
        assertThat(bytesHeld(item(batch, "ok1.pdf"))).isEqualTo(100L * 1024);
        assertThat(bytesHeld(item(batch, "ok2.pdf"))).isEqualTo(100L * 1024);
        assertThat(item(batch, "forged.pdf").getStagedObjectKey()).isNull();
        assertThat(items.sumStagedBytes(batch)).isEqualTo(200L * 1024);
    }

    // ------------------------------------------------------------------------------------------------ the other entrances

    @Test
    void theArchiveFromUploadSessionPath_enforcesTheSame() throws Exception {
        Fixture f = fixture();
        cap(MIB);
        TenantContext.set(f.tenantId(), f.userId());
        UUID batch = batchWith(f, "big.pdf", "ok.pdf");
        UUID session = UUID.randomUUID();
        Path forged = Files.createTempFile("doc10-session", ".zip");
        Path honest = Files.createTempFile("doc10-session", ".zip");
        try {
            Files.write(forged, zip(entries("big.pdf", 8 * MIB, 100)));
            Files.write(honest, zip(entries("ok.pdf", 100 * 1024, -1)));
            // the session store is not what is under test: it hands over the finished file, the rest is real
            Mockito.doReturn(forged).doReturn(honest).when(uploadSessions).takeCompletedFile(session);
            long filesBefore = filesUnder(f);

            ImportArchiveReceivedResponse first = intake.receiveFromSession(batch, session);
            assertThat(first.filesMatched()).isZero();
            assertThat(first.problems().get(0).message()).contains("not the size its header says");
            assertThat(filesUnder(f)).isEqualTo(filesBefore);
            assertThat(items.sumStagedBytes(batch)).isZero();

            ImportArchiveReceivedResponse second = intake.receiveFromSession(batch, session);
            assertThat(second.filesMatched()).isEqualTo(1);
            assertThat(bytesHeld(item(batch, "ok.pdf"))).isEqualTo(100L * 1024);
        } finally {
            Files.deleteIfExists(forged);
            Files.deleteIfExists(honest);
        }
    }

    @Test
    void theSingleFilePath_judgesTheBytesToo() throws Exception {
        Fixture f = fixture();
        cap(MIB);
        TenantContext.set(f.tenantId(), f.userId());
        UUID batch = batchWith(f, "a.pdf", "b.pdf");
        long filesBefore = filesUnder(f);
        byte[] content = new byte[300 * 1024];
        System.arraycopy("%PDF-1.4\n".getBytes(StandardCharsets.ISO_8859_1), 0, content, 0, 9);

        // a caller that declares less than it sends, and one that declares more
        assertThatThrownBy(() -> bulk.receiveFile(batch, "a.pdf", "application/pdf", 100,
                new ByteArrayInputStream(content))).isInstanceOf(BulkImportException.class)
                .extracting("code").isEqualTo("IMPORT_FILE_SIZE_MISMATCH");
        assertThatThrownBy(() -> bulk.receiveFile(batch, "a.pdf", "application/pdf", content.length + 500,
                new ByteArrayInputStream(content))).isInstanceOf(BulkImportException.class)
                .extracting("code").isEqualTo("IMPORT_FILE_SIZE_MISMATCH");
        assertThat(filesUnder(f)).isEqualTo(filesBefore);
        assertThat(item(batch, "a.pdf").getStagedObjectKey()).isNull();

        // the honest call, as the controller makes it (the multipart size), is unchanged
        bulk.receiveFile(batch, "a.pdf", "application/pdf", content.length, new ByteArrayInputStream(content));
        assertThat(item(batch, "a.pdf").getStagedSizeBytes()).isEqualTo(content.length);
        assertThat(bytesHeld(item(batch, "a.pdf"))).isEqualTo(content.length);
    }

    // ------------------------------------------------------------------------------------------------ partial writes

    @Test
    void aStreamThatFailsPartWay_leavesNoPartialObject_andNoItem() throws Exception {
        Fixture f = fixture();
        cap(MIB);
        TenantContext.set(f.tenantId(), f.userId());
        UUID batch = batchWith(f, "a.pdf");
        long filesBefore = filesUnder(f);
        long keysBefore = keyRows(f);
        byte[] head = "%PDF-1.4\n".getBytes(StandardCharsets.ISO_8859_1);
        InputStream failing = new InputStream() {
            private int sent;

            @Override
            public int read() throws IOException {
                if (sent >= 200 * 1024) {
                    throw new IOException("connection reset");
                }
                return sent < head.length ? head[sent++] : (sent++ & 0x7f);
            }
        };

        assertThatThrownBy(() -> bulk.receiveFile(batch, "a.pdf", "application/pdf", 250 * 1024, failing))
                .isInstanceOf(RuntimeException.class);

        assertThat(filesUnder(f)).as("the half-written object was removed").isEqualTo(filesBefore);
        assertThat(keyRows(f)).isEqualTo(keysBefore);
        assertThat(item(batch, "a.pdf").getStagedObjectKey()).isNull();
        assertThat(items.sumStagedBytes(batch)).isZero();
    }
}
