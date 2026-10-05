package com.oak.dms.scanning.helper;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** A ZIP is accepted only when it's complete, readable, unencrypted and within what the scanner can check. */
class ZipArchiveCheckTest {

    private static final int MAX_ENTRIES = 10;
    private static final long MAX_UNPACKED = 1_000_000;

    /** A ZIP of name/content pairs, deflated. */
    static byte[] zip(String... namesAndContents) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndContents[i]));
                zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static ZipArchiveCheck.Result check(byte[] zip) {
        return ZipArchiveCheck.check(new ByteArrayInputStream(zip), MAX_ENTRIES, MAX_UNPACKED);
    }

    @Test
    void aGoodZip_isValid() throws IOException {
        ZipArchiveCheck.Result result = check(zip("a.txt", "alpha", "docs/b.txt", "beta".repeat(100)));

        assertThat(result.outcome()).isEqualTo(ZipArchiveCheck.Outcome.VALID);
        assertThat(result.entries()).isEqualTo(2);
        assertThat(result.uncompressedBytes()).isEqualTo(5 + 400);
    }

    @Test
    void aTruncatedZip_isRefusedAsDamaged() throws IOException {
        byte[] whole = zip("a.txt", "alpha".repeat(500), "b.txt", "beta".repeat(500));

        for (int cut : new int[]{whole.length / 2, whole.length - 10, 40}) {
            ZipArchiveCheck.Result result = check(Arrays.copyOf(whole, cut));
            assertThat(result.outcome()).as("cut at " + cut).isEqualTo(ZipArchiveCheck.Outcome.INVALID);
            assertThat(result.message()).contains("damaged or incomplete");
        }
    }

    @Test
    void corruptedData_failsItsCrc() throws IOException {
        byte[] zip = zip("a.txt", "the same line again and again\n".repeat(200));
        zip[50] ^= 0x5A; // inside the first entry's deflated data

        assertThat(check(zip).outcome()).isEqualTo(ZipArchiveCheck.Outcome.INVALID);
    }

    @Test
    void anEmptyZip_isRefused() throws IOException {
        ZipArchiveCheck.Result result = check(zip());

        assertThat(result.outcome()).isEqualTo(ZipArchiveCheck.Outcome.INVALID);
        assertThat(result.message()).contains("empty");
    }

    @Test
    void pastTheScannersLimits_isRefused() throws IOException {
        String[] many = new String[(MAX_ENTRIES + 1) * 2];
        for (int i = 0; i < MAX_ENTRIES + 1; i++) {
            many[2 * i] = "f" + i + ".txt";
            many[2 * i + 1] = "x";
        }
        ZipArchiveCheck.Result tooMany = check(zip(many));
        assertThat(tooMany.outcome()).isEqualTo(ZipArchiveCheck.Outcome.INVALID);
        assertThat(tooMany.message()).contains("more than " + MAX_ENTRIES + " files");

        // A small archive that unpacks huge - a "zip bomb" - is stopped as soon as it passes the limit.
        ZipArchiveCheck.Result bomb = check(zip("zeros.bin", "\0".repeat((int) MAX_UNPACKED + 1)));
        assertThat(bomb.outcome()).isEqualTo(ZipArchiveCheck.Outcome.INVALID);
        assertThat(bomb.message()).contains("Unpacked");
    }

    @Test
    void aPasswordProtectedEntry_isReportedAsEncrypted() throws IOException {
        // A stored entry with general-purpose flag bit 0 (encrypted) set in its local header.
        byte[] content = "secret".getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(content);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            ZipEntry entry = new ZipEntry("secret.txt");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(content.length);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(content);
            zip.closeEntry();
        }
        byte[] zip = out.toByteArray();
        zip[6] |= 1;

        assertThat(check(zip).outcome()).isEqualTo(ZipArchiveCheck.Outcome.ENCRYPTED);
    }

    @Test
    void notAZipAtAll_isRefused() {
        byte[] text = "PK\u0003\u0004 but then nothing a ZIP reader understands".getBytes(StandardCharsets.ISO_8859_1);

        assertThat(check(text).outcome()).isEqualTo(ZipArchiveCheck.Outcome.INVALID);
    }
}
