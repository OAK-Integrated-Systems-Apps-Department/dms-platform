package com.oak.dms.importexport.service;

import com.oak.dms.importexport.exception.BulkImportException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** B2 file matching: template filename cell -> staged ZIP entry or loose file. */
class ImportFilesTest {

    @TempDir
    Path dir;

    @Test
    void matchesExactPath_thenUniqueName_thenIgnoringCase() throws Exception {
        Path batch = Files.createDirectories(dir.resolve("batch"));
        zip(batch.resolve(ImportFiles.ARCHIVE), "2026/jan/a.pdf", "A", "2026/feb/b.pdf", "B",
                "__MACOSX/2026/._a.pdf", "junk", "2026/feb/Report.PDF", "R");

        try (ImportFiles files = ImportFiles.open(List.of(batch), 100)) {
            assertThat(read(files, "2026/jan/a.pdf")).isEqualTo("A");
            assertThat(read(files, ".\\2026\\jan\\a.pdf")).isEqualTo("A"); // Windows-style path
            assertThat(read(files, "b.pdf")).isEqualTo("B");
            assertThat(read(files, "report.pdf")).isEqualTo("R");
            assertThat(files.resolve("missing.pdf")).isInstanceOf(ImportFiles.NotFound.class);
        }
    }

    @Test
    void sameNameInTwoFolders_isAmbiguousUnlessThePathIsGiven() throws Exception {
        Path batch = Files.createDirectories(dir.resolve("batch"));
        zip(batch.resolve(ImportFiles.ARCHIVE), "x/scan.pdf", "X", "y/scan.pdf", "Y");

        try (ImportFiles files = ImportFiles.open(List.of(batch), 100)) {
            assertThat(files.resolve("scan.pdf")).isEqualTo(new ImportFiles.Ambiguous(2));
            assertThat(read(files, "y/scan.pdf")).isEqualTo("Y");
        }
    }

    @Test
    void looseFiles_andAResubmissionFallingBackToTheOriginalBatch() throws Exception {
        Path original = Files.createDirectories(dir.resolve("original").resolve(ImportFiles.LOOSE_DIR));
        Files.writeString(original.resolve("old.pdf"), "OLD");
        Files.writeString(original.resolve("fixed.pdf"), "STALE");
        Path resubmit = Files.createDirectories(dir.resolve("resubmit").resolve(ImportFiles.LOOSE_DIR));
        Files.writeString(resubmit.resolve("fixed.pdf"), "NEW");

        try (ImportFiles files = ImportFiles.open(List.of(dir.resolve("resubmit"), dir.resolve("original"),
                dir.resolve("gone")), 100)) {
            assertThat(read(files, "fixed.pdf")).isEqualTo("NEW"); // the new upload wins
            assertThat(read(files, "old.pdf")).isEqualTo("OLD");   // reused, not uploaded again
            assertThat(((ImportFiles.Found) files.resolve("old.pdf")).file().size()).isEqualTo(3);
        }
    }

    @Test
    void notAZip_orTooManyEntries_isRefused() throws Exception {
        Path bad = Files.createDirectories(dir.resolve("bad"));
        Files.writeString(bad.resolve(ImportFiles.ARCHIVE), "definitely not a zip");
        assertThatThrownBy(() -> ImportFiles.open(List.of(bad), 100))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("isn't a valid ZIP");

        Path big = Files.createDirectories(dir.resolve("big"));
        zip(big.resolve(ImportFiles.ARCHIVE), "1", "a", "2", "b", "3", "c");
        assertThatThrownBy(() -> ImportFiles.open(List.of(big), 2))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("more than 2 entries");
    }

    @Test
    void safeName_reducesAnUploadedNameToAPlainBaseName() {
        assertThat(ImportStagingArea.safeName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(ImportStagingArea.safeName("C:\\Users\\me\\scan:1.pdf")).isEqualTo("scan_1.pdf");
        assertThatThrownBy(() -> ImportStagingArea.safeName("..")).isInstanceOf(BulkImportException.class);
        assertThatThrownBy(() -> ImportStagingArea.safeName(null)).isInstanceOf(BulkImportException.class);
    }

    private static String read(ImportFiles files, String reference) throws Exception {
        ImportFiles.Resolution resolution = files.resolve(reference);
        assertThat(resolution).as(reference).isInstanceOf(ImportFiles.Found.class);
        try (InputStream in = ((ImportFiles.Found) resolution).file().open()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void zip(Path target, String... namesAndContents) throws Exception {
        try (OutputStream out = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndContents[i]));
                zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
